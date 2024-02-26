package bguspl.set.ex;

import bguspl.set.Env;

import java.util.LinkedList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Collections;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True if game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * The slots of cards that we need to remove from table in next remove action.
     */
    private LinkedList<Integer> slotsToRemove = new LinkedList<>();

    /**
     * Represents almost a second in millis, for waking up the dealer for timer countdown update.
     */
    private final long ALMOST_SECOND_IN_MILLIS = 985;

    /**
     * Used for dealer to know when game ends or no sets on table, on function findSets.
     */
    private final int MAX_SETS_FOR_RESHUFFLE = 1;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        runPlayersThreads();
        while (!shouldFinish()) {
            placeCardsOnTable();
            updateTimerDisplay(true);
            timerLoop();
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && (System.currentTimeMillis() < reshuffleTime || env.config.turnTimeoutMillis == 0)) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // Iterating reverse order in order to terminate all threads gracefully
        for (int playerNumber = players.length - 1; playerNumber >= 0; playerNumber--) {
            players[playerNumber].terminate();
            while (players[playerNumber].playerThread.isAlive()) {
                try {
                    players[playerNumber].playerThread.join();
                } catch (InterruptedException ignored) {
                }
            }
        }
        this.terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if (!slotsToRemove.isEmpty()) {
            // If there is a set found to remove, synchronize on the table and remove it.
            try {
                table.tableSemaphore.acquire();
            } catch (InterruptedException ignored) {
            }
            // TODO: remove randomly from slots - maybe add a function removeCards in Table class and call it
            for (int slot : slotsToRemove) {
                // Remove the cards from the slots on the table.
                this.table.removeCard(slot);
            }
            table.tableSemaphore.release();
            // Clears the list when finished removing the cards
            slotsToRemove.clear();
        } else if (env.util.findSets(table.getAllCards(), MAX_SETS_FOR_RESHUFFLE).isEmpty()) {
            // If there are no sets on table, synchronize on the table and remove all cards.
            removeAllCardsFromTable();
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // Reshuffle the deck for random cards drawn
        reshuffleDeck();
        // The amount of missing places for cards on the table is (table size - current number of cards on table)
        int missingCardsCount = env.config.tableSize - table.countCards();
        LinkedList<Integer> cardsToPlace = new LinkedList<>();
        for (int cardNum = 0; cardNum < missingCardsCount && !deck.isEmpty(); cardNum++) {
            // Add to the list of cards needed to be places on the table . Taken from the deck, while it's not empty.
            cardsToPlace.add(deck.remove(deck.size() - 1));
        }
        if (!cardsToPlace.isEmpty()) {
            // Synchronize on the table object while placing new cards on table
            try {
                table.tableSemaphore.acquire();
                // Call the table function to update the data and ui.
                table.placeCardsOnTable(cardsToPlace);
            } catch (InterruptedException ignored) {
            }

            table.tableSemaphore.release();
            // Display hints if needed.
            if (env.config.hints) {
                table.hints();
            }
            updateTimerDisplay(true);
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        // The thread waits until we need to update countdown , or to check set. We must synchronize when waiting
        synchronized (this) {
            try {
                this.wait(ALMOST_SECOND_IN_MILLIS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        boolean shouldWarn = false;
        long timeLeft = env.config.turnTimeoutMillis;
        if (timeLeft > 0) {
            if (reset) {
                reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            } else {
                timeLeft = reshuffleTime - System.currentTimeMillis();
                shouldWarn = timeLeft < env.config.turnTimeoutWarningMillis;
            }
            env.ui.setCountdown(timeLeft, shouldWarn);
        } else if (timeLeft == 0) {
            if (reset) {
                // If time reset show 0 and restart the clock to current dates
                env.ui.setElapsed(timeLeft);
                reshuffleTime = System.currentTimeMillis();
            } else {
                // The time elapsed from last reset
                env.ui.setElapsed((System.currentTimeMillis() - reshuffleTime));
            }
        }
        // If time left<0 display nothing
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        LinkedList<Integer> removedCardsList = new LinkedList<>();
        // Synchronize on the table while removing the cards.
        try {
            table.tableSemaphore.acquire();
            removedCardsList = table.removeAllCardsFromTable();
        } catch (InterruptedException ignored) {
        }
        table.tableSemaphore.release();
        // Merging deck and removedCardsList.
        deck.addAll(removedCardsList);
        if (env.util.findSets(deck, MAX_SETS_FOR_RESHUFFLE).isEmpty()) {
            // Terminate game if no sets on deck
            terminate();
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore = 0;
        List<Integer> winners = new ArrayList<>();
        // Iterate over all players to find the players with maximal score
        for (Player player : players) {
            if (maxScore < player.score()) {
                maxScore = player.score();
                // Update ids of the winder - according to the new maxScore
                winners.clear();
                winners.add(player.id);
            } else if (maxScore == player.score()) {
                winners.add(player.id);
            }
        }
        // Convert the winner List<Integer> to int array
        int[] finalWinners = winners.stream().mapToInt(Integer::intValue).toArray();
        env.ui.announceWinner(finalWinners);
    }

    /**
     * Check if the chosen cards of the given player create a valid set.
     *
     * @param id - the player id number.
     * @return - rather the set is valid or not.
     */
    public void isSetValid(int id) {
        int[] cards = table.getPlayerCards(id);
        boolean isSetValid = env.util.testSet(cards);
        // TODO : Inbar try to implement better
        if (isSetValid) {
            // If the set is valid - we need to remove the cards, so it updates the slotsToRemove list to the relevant slots.
            for (int card : cards) {
                this.slotsToRemove.add(table.cardToSlot[card]);
            }
            // Remove the set cards and give point to the player
            removeCardsFromTable();
            players[id].point();
            // Place new cards
            placeCardsOnTable();
        } else {
            // If the set still exists on the table - for avoiding check sets found at same time.
            if (!(cards.length == 0)) {
                players[id].penalty();
                for (int card : cards) {
                    // Remove the illegal set tokens
                    if ((table.cardToSlot[card] != null) && table.canRemoveToken(id, table.cardToSlot[card])) {
                        this.table.removeToken(id, table.cardToSlot[card]);
                    }
                }
            }
        }

    }

    /**
     * Init and run all players threads.
     */
    private void runPlayersThreads() {
        // The true flag, indicate it is fair Semaphore
        Semaphore semaphore = new Semaphore(1, true);
        for (int playerNumber = 0; playerNumber < players.length; playerNumber++) {
            // We initialize the semaphore here because we want to make sure it is the same one for all players
            players[playerNumber].setSemaphore(semaphore);
            Thread playerThread = new Thread(players[playerNumber], env.config.playerNames[playerNumber]);
            playerThread.start();
        }
    }

    /**
     * Reshuffles randomly the deck.
     */
    private void reshuffleDeck() {
        Collections.shuffle(deck);
    }
}

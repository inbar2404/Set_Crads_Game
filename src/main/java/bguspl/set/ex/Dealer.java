package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    private LinkedBlockingQueue<Integer> slotsToRemove = new LinkedBlockingQueue<Integer>();

    /**
     * Define the required beat (time "jumps") of the thread.
     */
    private long currentBeat;

    /**
     * Represents almost a second in millis, for waking up the dealer for timer countdown update.
     */
    private final long ALMOST_SECOND_IN_MILLIS = 985;

    /**
     * Represent the "jumps" (beat) we want to have when it is in the warn zone (last 5s).
     */
    private final long WARN_BEAT_TIME = 10;

    /**
     * Used for dealer to know when game ends or no sets on table, on function findSets.
     */
    private final int MAX_SETS_FOR_RESHUFFLE = 1;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.currentBeat = ALMOST_SECOND_IN_MILLIS;
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
            env.logger.info("playerThread " + players[playerNumber].playerThread.getName() + " terminated.");
            players[playerNumber].terminate();
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
        synchronized (table) {
            if (!slotsToRemove.isEmpty()) {
                // If there is a set found to remove, synchronize on the table and remove it.
                for (int slot : slotsToRemove) {
                    // Remove the cards from the slots on the table.
                    this.table.removeCard(slot);
                }
                // Clears the list when finished removing the cards
                slotsToRemove.clear();
            } else if (env.util.findSets(table.getAllCards(), MAX_SETS_FOR_RESHUFFLE).isEmpty()) {
                // If there are no sets on table, synchronize on the table and remove all cards.
                removeAllCardsFromTable();
            }
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
                this.wait(currentBeat);
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
        if (reset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        } else {
            timeLeft = reshuffleTime - System.currentTimeMillis();
            shouldWarn = timeLeft < env.config.turnTimeoutWarningMillis;
        }

        if (shouldWarn) {
            currentBeat = WARN_BEAT_TIME;
        } else {
            currentBeat = ALMOST_SECOND_IN_MILLIS;
        }

        if (timeLeft < 0) {
            timeLeft = 0;
        }
        env.ui.setCountdown(timeLeft, shouldWarn);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        LinkedList<Integer> removedCardsList = new LinkedList<>();
        // Synchronize on the table while removing the cards.
        synchronized (table) {
            removedCardsList = table.removeAllCardsFromTable();
        }
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
     * @param cards - the player cards of the player we want to check if he has a set.
     * @return - rather the set is valid or not.
     */
    public boolean isSetValid(int[] cards) {
        return env.util.testSet(cards);
    }

    /**
     * Removing the given cards and place other instead.
     *
     * @param cards - the given card we want to remove and bring others instead.
     */
    public void replaceCards(int[] cards) {
        // We need to remove the cards, so it updates the slotsToRemove list to the relevant slots.
        for (int card : cards) {
            int slot = table.cardToSlot[card];
            this.slotsToRemove.add(slot);
        }
        removeCardsFromTable();
        placeCardsOnTable();
    }

    /**
     * Remove token of illegal set that were placed.
     *
     * @param cards - the given cards where illegal tokens were placed.
     */
    public void removeIllegalSetTokens(int[] cards, int player) {
        // If the set still exists on the table - for avoiding check sets found at same time.
        if (!(cards.length == 0)) {
            for (int card : cards) {
                if (table.cardToSlot[card] != null) {
                    this.table.removeToken(player, table.cardToSlot[card]);
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

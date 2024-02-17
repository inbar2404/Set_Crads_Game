package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.List;
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
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

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
            timerLoop();
            updateTimerDisplay(false);
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
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
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
        // TODO implement
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // TODO implement
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        // TODO implement
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // TODO: Consult with Bar -  should I do a while of letting thread to sleep and each time update or it should be the timeLooper Responsibility?
        boolean shouldWarn = false;
        long timeLeft = env.config.turnTimeoutMillis;
        if (reset) {
            // TODO: Consult with Bar - I'm not sure my reshuffleTime calculation is correct
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        } else {
            timeLeft = reshuffleTime - System.currentTimeMillis();
            shouldWarn = timeLeft < env.config.turnTimeoutWarningMillis;
            // TODO: Waiting for answer in the Forum: in case of should warn, do I need to use also "void setElapsed(long millies)"?
        }
        env.ui.setCountdown(timeLeft, shouldWarn);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // TODO implement
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
    public boolean isSetValid(int id) {
        //TODO: Check if needed here a call to removeCardsFromTable(), and maybe a field of cardsToRemove to update for usage
        int[] cards = table.getPlayerCards(id);
        return env.util.testSet(cards);
    }

    /**
     * Init and run all players threads.
     */
    private void runPlayersThreads() {
        // The true flag, indicate it is fair Semaphore
        Semaphore semaphore = new Semaphore(1, true);
        for (int playerNumber = 0; playerNumber < players.length; playerNumber++) {
            // We init the semaphore here because we want to make sure it is the same one for all players
            players[playerNumber].setSemaphore(semaphore);
            Thread playerThread = new Thread(players[playerNumber], env.config.playerNames[playerNumber]);
            playerThread.start();
        }
    }
}

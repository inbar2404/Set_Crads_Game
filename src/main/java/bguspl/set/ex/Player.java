package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.LinkedBlockingQueue;


/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The waiting time for next time update - beat.
     */
    public static final long BEAT_TIME = 1000;

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    public Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * We use semaphore in order to implement the wait & notify mechanism
     */
    private Semaphore playersWaitingForSetCheckSemaphore;

    /**
     * User should have a queue of his actions to execute with his tokens.
     */
    private LinkedBlockingQueue<Integer> actions;

    /**
     * The main thread that handle the game, the "dealer".
     */
    private Dealer dealer;

    /**
     * A flag indicating if the player thread is available.
     */
    private boolean isPlayerWokenUp = true;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the main thread that handle the game, the "dealer".
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.terminate = false; // We want to init it to False
        this.actions = new LinkedBlockingQueue<Integer>(env.config.featureSize); // Number of actions should be equals to size of a set
        this.dealer = dealer;
    }

    /**
     * A setter for the semaphore object.
     *
     * @param semaphore - The semaphore object.
     */
    public void setSemaphore(Semaphore semaphore) {
        this.playersWaitingForSetCheckSemaphore = semaphore;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            // We will try to execute the user's actions
            int action = 0;
            try {
                // The player thread waits until he has action to perform
                action = this.actions.take();
            } catch (InterruptedException e) {
            }
            // Execute the action - rather is it placing or removing
            if (table.canPlaceToken(id, action)) {
                {
                    // Try to lock the table to put the token when allowed, and with no interruptions from other threads
                    if (table.tableSemaphore.tryAcquire()) {
                        table.placeToken(id, action);
                        table.tableSemaphore.release();
                    }
                }
            } else {
                // Removes the token if possible
                if ((table.canRemoveToken(id, action))) {
                    table.removeToken(id, action);
                }
            }
            // In case of 3 tokens that are placed on deck - we will check if we have a set
            boolean hasSet = table.getNumberOfTokensOfPlayer(id) == 3;
            if (hasSet) {
                try {
                    playersWaitingForSetCheckSemaphore.acquire();
                    // Notify the dealer to wake him up to check the set. We must synchronize here.
                    synchronized (dealer) {
                        dealer.notifyAll();
                    }
                    // Call the dealer function to check the set
                    dealer.isSetValid(this.id);
                } catch (InterruptedException ignored) {
                }
            }
        }
        // Try to stop thread in case of aiThread
        if (!human) try {
            aiThread.join();
        } catch (InterruptedException ignored) {
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO: Check if it works well when cards are init (that it's not starting to act before)
                // Getting a random slot to place a token
                Random random = new Random();
                int randomSlot = random.nextInt(env.config.tableSize);
                keyPressed(randomSlot);
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        this.terminate = true;
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    // TODO: Consider later how to handle the case of the "else" - only for aiThread
    public void keyPressed(int slot) {
        // Handle the key pressed only when the player is not in freeze
        if (this.actions.size() < this.env.config.featureSize & isPlayerWokenUp) {
            this.actions.add(slot);
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // Release the lock on the dealer
        playersWaitingForSetCheckSemaphore.release();
        freezePlayer(this.env.config.pointFreezeMillis);
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // Release the lock on the dealer
        playersWaitingForSetCheckSemaphore.release();
        freezePlayer(this.env.config.penaltyFreezeMillis);
    }

    /**
     * Freezing a player for a given time.
     *
     * @param time - the time to freeze this player
     */
    private void freezePlayer(long time) {

        // We update the freeze time in the ui for the relevant player
        env.ui.setFreeze(this.id, time);
        // The player sleeps and can wake up only for updating timer, not for getting new key presses
        isPlayerWokenUp = false;
        // As long as time not over - sleep for the defined beat and then update the remain time
        while (time > 0) {
            try {
                playerThread.sleep(Player.BEAT_TIME);
            } catch (InterruptedException ignored) {
            }
            time -= Player.BEAT_TIME;
            env.ui.setFreeze(this.id, time);
        }
        // Finish the freeze
        isPlayerWokenUp = true;

    }

    public int score() {
        return score;
    }
}

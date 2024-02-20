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
    private Semaphore semaphore;

    /**
     * User should have a queue of his actions to execute with his tokens.
     */
    private LinkedBlockingQueue<Integer> actions;

    /**
     * The main thread that handle the game, the "dealer".
     */
    private Dealer dealer;

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
        this.semaphore = semaphore;
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
            } catch (InterruptedException ignored) {
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
            // TODO: Meni says it is not good like that - use notify instead and pass all logic to the dealer
            if (hasSet) {
                try {
                    semaphore.acquire();
                    if (dealer.isSetValid(this.id)) {
                        point();
                    } else {
                        penalty();
                    }
                } catch (InterruptedException ignored) {
                }
                semaphore.release();
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
        Thread.currentThread().interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    // TODO: Consider later how to handle the case of the "else" - only for aiThread
    public void keyPressed(int slot) {
        if (this.actions.size() < this.env.config.featureSize) {
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
        freezePlayer(this.env.config.pointFreezeMillis);
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        freezePlayer(this.env.config.penaltyFreezeMillis);
    }

    /**
     * Freezing a player for a given time.
     *
     * @param time - the time to freeze this player
     */
    private void freezePlayer(long time) {
        // TODO while player is freeze, it's still getting his actions and not ignoring them, and then when finished freezing, the table updates
        // For example: When player put 3 tokens that are not set, he can press on the slots and it will remove it when he finish his freeze, instead of ignore them.

        // We update the freeze time in the ui for the relevant player
        env.ui.setFreeze(this.id, time);
        // As long as time not over - sleep for the defined beat and then update the remain time
        while (time > 0) {
            try {
                Thread.sleep(Player.BEAT_TIME);
            } catch (InterruptedException ignored) {
            }
            time -= Player.BEAT_TIME;
            env.ui.setFreeze(this.id, time);
        }
    }

    public int score() {
        return score;
    }
}

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
    private Thread playerThread;

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
    // TODO: Consult with Bar - maybe we should create FaireSemaphore (or even reader-writer) and use it instead?
    private Semaphore semaphore;

    /**
     * User should have a queue of his actions to execute with his tokens.
     */
    private LinkedBlockingQueue<Integer> actions;

    /**
     * The dealer in the game.
     */
    private final Dealer dealer; // TODO: It is look very wrong that a player should knows his dealer.

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
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
        this.semaphore = new Semaphore(1);
        this.actions = new LinkedBlockingQueue<Integer>(env.config.featureSize); // Number of actions should be equals to size of a set
        this.dealer = dealer;
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
            if (this.actions.size() > 0)
            {
                int action = this.actions.poll();
                // Execute the action - rather is it placing or removing
                // TODO: Consult with Bar if using synchronized in this if-else scope is the best solution?
                if(table.canPlaceToken(id, action)) {
                    synchronized (table) {
                        table.placeToken(id, action);
                    }
                }
                else {
                    synchronized (table) {
                        table.removeToken(id, action);
                    }
                }
                // TODO: Consult with Bar - is this code should be here or in the dealer class?
                // In case of 3 tokens that are placed on deck - we will check if we have a set
                boolean hasSet = table.getNumberOfTokensOfPlayer(id)==3;
                if (hasSet) {
                    try {
                        semaphore.acquire();
                        // TODO: It feels very wrong to have here an object of dealer
                        if(dealer.isSetValid(this.id)){
                            point();
                        }
                        else {
                            penalty();
                        }
                    }
                    catch (InterruptedException ignored) {}
                    semaphore.release();
                }
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
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
                try {
                    Thread.sleep(1); // TODO: Consult with Bar: is this the best implementation? what other options are there?
                } catch (InterruptedException ignored) {}
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
        // TODO: Consult with Bar: I think I should add - Thread.currentThread().interrupt();  what do you think?
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
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
     * @param time - the time to freeze this player
     */
    private void freezePlayer(long time){
        env.ui.setFreeze(this.id,time);
        while (time > 0) {
            try {
                Thread.sleep(env.config.BEAT_TIME);
            } catch (InterruptedException ignored) {}
            time -= env.config.BEAT_TIME;
            env.ui.setFreeze(this.id, time);
        }
    }

    public int score() {
        return score;
    }
}

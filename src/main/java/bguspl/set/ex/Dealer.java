package bguspl.set.ex;

import bguspl.set.Env;

import java.util.LinkedList;
import java.util.List;
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

    /**
     * The cards slots that we need to remove from table in next remove action.
     */
    // TODO update this list on isSetValid()
    private LinkedList<Integer> cardsSlotsToRemove = new LinkedList<>();

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
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
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
        // TODO implement
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
        // TODO check if needed synchronize
        // TODO update cardsSlotsToRemove field on isSetValid(to the set slots) and on countdown timeout(to 0-11 slots)
        for (int cardsSlot : cardsSlotsToRemove) {
            if(table.canRemoveCard(cardsSlot))
                table.removeCard(cardsSlot);
        }
        // Clears the vector when finished removing the cards
        cardsSlotsToRemove.clear();
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // TODO check if need synchronize
        // The amount of missing places for cards on the table is (table size - current number of cards on table)
        int missingCardsCount =  env.config.tableSize - table.countCards();
        LinkedList<Integer> cardsToPlace = new LinkedList<>();
        for (int cardNum =0; cardNum <missingCardsCount && !deck.isEmpty() ; cardNum++)
        {
            //add to list of cards to place on table from. taken from the deck, while it's not empty
            cardsToPlace.add(deck.remove(deck.size()-1));
        }
        //call the table function to update the data and ui
        table.placeCardsOnTable(cardsToPlace);
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
        // TODO implement
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // TODO: check if needed synchronize
        LinkedList<Integer> removedCardsList = table.removeAllCardsFromTable();
        // Merging deck and removedCardsList
        deck.addAll(removedCardsList);
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
    }

    /**
     * Check if the chosen cards of the given player create a valid set.
     * @param player   - the player id number.
     * @return         - rather the set is valid or not.
     */
    // TODO: see if there is a better solution for that
    public static boolean isSetValid(int player) {
        // TODO implement
        return true;
    }
}

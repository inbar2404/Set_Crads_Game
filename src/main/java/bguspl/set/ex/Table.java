package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    /**
     * Mapping between a player and the tokens slots he has.
     */
    protected LinkedList<LinkedList<Integer>> playersTokensMap;


    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;

        //initialize list of lists players-to-tokens
        playersTokensMap = new LinkedList<>();
        for (int i = 0; i < env.config.players; i++) {
            playersTokensMap.add(new LinkedList<>());
        }
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot. (it overrides the card in the slot if exists)
     */
    public void placeCard(int card, int slot) {
        try {
            //sleep the table delay time
            Thread.sleep(env.config.tableDelayMillis);
        }
        catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;

        //call the ui to actually place the card on graphics - the ui class throws exception if out of bounds
        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        try {
            //sleep the table delay time
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        if(slotToCard[slot] != null) {
            //if there is a card on the slot - update the relevant cell in arrays to null
            int card = slotToCard[slot];
            cardToSlot[card] = null;
            slotToCard[slot] = null;
            //remove the slot from all players-tokens lists
            removeFromAllLists(slot);
            //call the ui to actually remove the card and tokens from graphics - the ui class throws exception if out of bounds
            env.ui.removeCard(slot);
            env.ui.removeTokens(slot);
        }
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        //add the slot to the player-to-tokens mapping, if it didn't exist already , and there is a card to place on it
        if(!(playersTokensMap.get(player).contains(slot)) && (slotToCard[slot] != null))
        {
            playersTokensMap.get(player).add(slot);
            env.ui.placeToken(player, slot);
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        //remove the slot from the player-to-tokens mapping, if it  exists , and there is a card which it's placed on
        if ((getTokens(player).contains(slot)) && (slotToCard[slot] != null)) {
            getTokens(player).remove((Integer) slot);
            env.ui.removeToken(player, slot);
            return true;
        }
        else {
            return false;
        }
    }

    public LinkedList<Integer> getTokens(int player)
    {
        //return given player tokens slots list
        return playersTokensMap.get(player);
    }

    /**
     *    Method to remove a given slot from all lists
     */
    public void removeFromAllLists(int slot) {
        for (LinkedList<Integer> list : playersTokensMap) {
            // Remove the number if it exists in the list
            list.removeIf(number -> number.equals(slot));
        }
    }

}

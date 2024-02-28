package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.Arrays;

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
    protected HashMap<Integer, LinkedList<Integer>> playersTokensMap;



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
        this.playersTokensMap = new HashMap<>();
        // Initialize hash map to playerId-tokens mapping
        for (int playerID = 0; playerID < env.config.players; playerID++) {
            this.playersTokensMap.put(playerID, new LinkedList<Integer>());
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
     *
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     * @post - the card placed is on the table, in the assigned slot. (it overrides the card in the slot if exists)
     */
    public void placeCard(int card, int slot) {
        try {
            // Sleep the table delay time
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }
        // Update to keep the inv
        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        // Call the ui to actually place the card on graphics
        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card on the table in a grid slot.
     *
     * @param slot - the slot in which the card should be removed.
     * @post - the card removed from the table, from the assigned slot.
     */
    public void removeCard(int slot) {
        try {
            // Sleep the table delay time
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }

        // Update the relevant cell in arrays to null
        int card = slotToCard[slot];
        cardToSlot[card] = null;
        slotToCard[slot] = null;
        // Remove the slot from all players-tokens lists
        removeFromAllLists(slot);
        // Call the ui to actually remove the card and tokens from graphics - the ui class throws exception if out of bounds
        env.ui.removeCard(slot);
        env.ui.removeTokens(slot);
    }

    /**
     * Places a player token on a grid slot.
     *
     * @param slot - the slot on which trying place the token.
     * @return - true iff a card can be removed, when its slot is not null .
     */
    public boolean canRemoveCard(int slot) {
        return (slot < env.config.tableSize && slotToCard[slot] != null);
    }

    /**
     * Place a token of a player from a grid slot.
     *
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     */
    public void placeToken(int player, int slot) {
        // Add the slot to the player-to-tokens mapping, and update ui
        getTokens(player).add(slot);
        env.ui.placeToken(player, slot);
    }


    /**
     * Checks if the player can place the token in the given slot number.
     *
     * @param player - the player the token belongs to.
     * @param slot   - the number of the slot to place.
     * @return - rather is it possible or not.
     */
    public boolean canPlaceToken(int player, int slot) {
        // Return true if token didn't exist already, there is a card to place on it , and not reached the max tokens number
        return (!existingTokenPlace(player, slot) && (slotToCard[slot] != null) && getNumberOfTokensOfPlayer(player) < env.config.featureSize);
    }


    /**
     * Removes a token of a player from a grid slot.
     *
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return - true ifF a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        // Remove the token from the player-to-tokens mapping, and from the ui
        if (canRemoveToken(player, slot)) {
            getTokens(player).remove((Integer) slot);
            env.ui.removeToken(player, slot);
            return true;
        }
        return false;
    }

    /**
     * Checks if the player can remove the token in the given slot number.
     *
     * @param player - the player the token belongs to.
     * @param slot   - the number of the slot that asked to remove the token from.
     * @return - rather is it possible or not.
     */
    public boolean canRemoveToken(int player, int slot) {
        // True if it exists ,  there is a card which it's placed on
        return (slotToCard[slot] != null) && existingTokenPlace(player, slot);
    }

    /**
     * Checks if the player can remove the token in the given slot number.
     *
     * @param player - the player the token belongs to.
     * @param slot   - the number of the slot that asked to check the token from.
     * @return - rather is it exist there or not.
     */
    private boolean existingTokenPlace(int player, int slot) {
        return (getTokens(player).contains(slot));
    }

    /**
     * @param player - the player the token belongs to.
     * @return given player tokens slots list.
     */
    protected LinkedList<Integer> getTokens(int player) {
        return playersTokensMap.get(player);
    }

    /**
     * @param slot - the number of the slot that asked to be removed from lists.
     *             Method to remove a given slot from all lists.
     */
    private void removeFromAllLists(int slot) {
        for (LinkedList<Integer> list : playersTokensMap.values()) {
            // Remove the number if it exists in the list
            list.removeIf(number -> number.equals(slot));
        }
    }

    /**
     * Count how many of all tokens are belong to the given player.
     *
     * @param player - the player the tokens belongs to.
     * @return - the number of tokens.
     */
    public int getNumberOfTokensOfPlayer(int player) {
        return playersTokensMap.get(player).size();
    }


    /**
     * Removes all cards existing on the table.
     *
     * @return - A list of the removed cards.
     */
    public LinkedList<Integer> removeAllCardsFromTable() {
        LinkedList<Integer> removedCardsList = new LinkedList<>();

        for (int slot = 0; slot < slotToCard.length; slot++) {
            // Run through all slots and remove the cards from the not empty ones
            if (slotToCard[slot] != null) {
                removedCardsList.add(slotToCard[slot]);
                removeCard(slot);
            }
        }
        return removedCardsList;
    }

    /**
     * Places cards given from the dealer on empty slots.
     *
     * @param cardsToPlace - List of cards to place on the empty slots on the table.
     */
    public void placeCardsOnTable(LinkedList<Integer> cardsToPlace) {
        // Create a LinkedList to store numbers from 0 to 11
        LinkedList<Integer> randomSlots = new LinkedList<>();
        for (int slotNum = 0; slotNum < slotToCard.length; slotNum++) {
            randomSlots.add(slotNum);
        }
        // Shuffle the slots
        Collections.shuffle(randomSlots);

        for (int slot = 0; slot < randomSlots.size() && !cardsToPlace.isEmpty(); slot++) {
            // Run through all slots and place the cards from the list on the empty ones , while there are new cards to place. synchronize to avoid null ptr
            synchronized (this) {
                if (slotToCard[randomSlots.get(slot)] == null) {
                    placeCard(cardsToPlace.removeLast(), randomSlots.get(slot));
                }
            }
        }
    }

    /**
     * @param player - The player's id who we want to find his selected cards.
     * @return -  Array of player cards.
     */
    public int[] getPlayerCards(int player) {
        LinkedList<Integer> slots = getTokens(player);
        int[] cards = new int[slots.size()];
        for (int currentSlot = 0; currentSlot < slots.size(); currentSlot++) {
            cards[currentSlot] = this.slotToCard[slots.get(currentSlot)];
        }
        return cards;
    }

    /**
     * @return - All cards on table in linked list format.
     */
    public LinkedList<Integer> getAllCards() {
        LinkedList<Integer> allCardsList = new LinkedList<>();
        for (Integer card : slotToCard) {
            if (card != null) {
                allCardsList.add(card);
            }
        }
        return allCardsList;
    }
}


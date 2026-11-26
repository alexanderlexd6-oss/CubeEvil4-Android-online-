package com.ast.re4decomp;

/**
 * Who plays with what in the split-screen / co-op test (26.11.2026). Player 1 is the way the game was played so far:
 * the touch controls and every controller that is not player 2's. Player 2 is one physical controller chosen in the
 * "Split screen" menu, or none. No Android classes here, so the rules can be tested on a PC.
 *
 * A controller is remembered by its descriptor (InputDevice.getDescriptor: the same controller keeps it when it is
 * plugged in again), not by its device id, which Android changes at every connection.
 */
final class PlayerSlots {
  /** No device. */
  static final int NONE = -1;

  private PlayerSlots() {}

  /**
   * The device id of player 2's controller among the connected ones: the one whose descriptor is `saved`.
   * NONE when nothing is saved or the controller is not connected now. `ids` and `descriptors` go together.
   */
  static int pick(String saved, int[] ids, String[] descriptors) {
    if (saved == null || saved.isEmpty() || ids == null || descriptors == null) return NONE;
    int n = Math.min(ids.length, descriptors.length);
    for (int i = 0; i < n; i++) if (saved.equals(descriptors[i])) return ids[i];
    return NONE;
  }

  /**
   * Whether the connected controllers allow two players at all: player 2 needs a controller of its own, and player 1
   * keeps the touch controls, so one controller is enough (it is player 2's), two let both play on controllers.
   */
  static boolean canSplit(int connectedControllers) {
    return connectedControllers >= 1;
  }

  /** The line under the menu's list: what player 1 plays with, given the controllers and player 2's choice. */
  static String playerOneUses(int connectedControllers, boolean player2Chosen) {
    int left = connectedControllers - (player2Chosen ? 1 : 0);
    if (left <= 0) return "Touch controls";
    return left == 1 ? "Touch controls and 1 controller" : "Touch controls and " + left + " controllers";
  }
}

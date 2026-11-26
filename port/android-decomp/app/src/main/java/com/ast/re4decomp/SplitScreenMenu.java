package com.ast.re4decomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Split screen" menu of the start screen (26.11.2026): finds the connected controllers and lets the player choose
 * which one is player 2's. Player 1 keeps the touch controls and every other controller. The choice is saved by the
 * controller's descriptor (PlayerSlots) and applied by CoreActivity (CoreInput.setPlayer2), which sends player 2's
 * controller to the core's channel 1.
 *
 * This is the controller side only: a second player in the game itself (and the split picture) does not exist yet, so
 * the menu says so.
 */
final class SplitScreenMenu {
  static final String PREFS = "split_screen", KEY_P2 = "player2";

  private SplitScreenMenu() {}

  /** The controllers connected now (gamepads and joysticks, not the virtual ones of Android). */
  static List<InputDevice> controllers() {
    List<InputDevice> out = new ArrayList<>();
    for (int id : InputDevice.getDeviceIds()) {
      InputDevice d = InputDevice.getDevice(id);
      if (d == null || d.isVirtual()) continue;
      int src = d.getSources();
      boolean pad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
          || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
      if (pad) out.add(d);
    }
    return out;
  }

  /** The device id of player 2's saved controller among the connected ones; PlayerSlots.NONE without one. */
  static int player2Device(Context c) {
    String saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_P2, "");
    List<InputDevice> all = controllers();
    int[] ids = new int[all.size()];
    String[] descriptors = new String[all.size()];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = all.get(i).getId();
      descriptors[i] = all.get(i).getDescriptor();
    }
    return PlayerSlots.pick(saved, ids, descriptors);
  }

  /** Opens the menu over the given screen. */
  static void show(Activity a) {
    final SharedPreferences prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    final String saved = prefs.getString(KEY_P2, "");
    final List<InputDevice> pads = controllers();

    LinearLayout box = new LinearLayout(a);
    box.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (16 * a.getResources().getDisplayMetrics().density);
    box.setPadding(pad, pad, pad, 0);

    TextView intro = new TextView(a);
    intro.setTextSize(14);
    intro.setText("Player 1 plays with the touch controls and the other controllers. "
        + "Choose the controller of player 2.");
    box.addView(intro);

    final RadioGroup group = new RadioGroup(a);
    group.setPadding(0, pad / 2, 0, pad / 2);
    final List<String> descriptors = new ArrayList<>();
    RadioButton none = new RadioButton(a);
    none.setText("No second player");
    none.setId(1000);
    group.addView(none);
    descriptors.add("");
    boolean found = saved.isEmpty();
    for (int i = 0; i < pads.size(); i++) {
      InputDevice d = pads.get(i);
      RadioButton r = new RadioButton(a);
      r.setText(d.getName());
      r.setId(1001 + i);
      group.addView(r);
      descriptors.add(d.getDescriptor());
      if (saved.equals(d.getDescriptor())) {
        r.setChecked(true);
        found = true;
      }
    }
    if (found && saved.isEmpty()) none.setChecked(true);
    if (!found) none.setChecked(true);  // the saved controller is not connected now
    box.addView(group, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT));

    TextView status = new TextView(a);
    status.setTextSize(13);
    boolean chosen = !saved.isEmpty() && found;
    if (pads.isEmpty()) {
      status.setText("No controller found. Connect one (Bluetooth or USB) and open this menu again.");
    } else {
      status.setText("Player 1: " + PlayerSlots.playerOneUses(pads.size(), chosen) + ".");
    }
    box.addView(status);

    TextView note = new TextView(a);
    note.setTextSize(13);
    note.setPadding(0, pad / 2, 0, 0);
    note.setText("Work in progress: this only assigns the controllers. The second player and the split picture "
        + "inside the game are not built yet.");
    box.addView(note);

    new AlertDialog.Builder(a)
        .setTitle("Split screen")
        .setView(box)
        .setPositiveButton("Save", (dlg, w) -> {
          int idx = group.getCheckedRadioButtonId() - 1000;
          String pick = idx >= 0 && idx < descriptors.size() ? descriptors.get(idx) : "";
          prefs.edit().putString(KEY_P2, pick).apply();
          Toast.makeText(a, pick.isEmpty() ? "No second player" : "Player 2 controller saved", Toast.LENGTH_SHORT)
              .show();
        })
        .setNegativeButton("Cancel", null)
        .show();
  }
}

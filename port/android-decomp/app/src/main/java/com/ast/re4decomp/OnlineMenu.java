package com.ast.re4decomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** The "Online" menu of the start screen: player name, list of servers, create a server, join one directly.
 *  First version: only the interface and the saved settings. The network comes in the next step. */
final class OnlineMenu {
  private static final String PREFS = "online";
  private static final String KEY_NAME = "name", KEY_SERVER = "serverName", KEY_PORT = "port", KEY_LAST = "lastAddress";
  static final int DEFAULT_PORT = 7777;

  /** One server of the list (filled by the network part later). */
  static final class Server {
    final String name, host;
    final int port;
    Server(String name, String host, int port) { this.name = name; this.host = host; this.port = port; }
  }

  /** The servers found so far. Empty until the search is built. */
  private static final List<Server> found = new ArrayList<>();

  private OnlineMenu() {}

  /** The saved player name ("" if none yet). The network part will read it from here. */
  static String playerName(Context c) { return prefs(c).getString(KEY_NAME, ""); }

  private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

  private static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

  private static TextView label(Activity a, LinearLayout box, String text) {
    TextView t = new TextView(a);
    t.setText(text);
    t.setTextSize(16);
    t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    t.setPadding(0, dp(a, 12), 0, dp(a, 2));
    box.addView(t);
    return t;
  }

  private static Button button(Activity a, LinearLayout box, String text, Runnable action) {
    Button b = new Button(a);
    b.setText(text);
    b.setOnClickListener(v -> action.run());
    box.addView(b);
    return b;
  }

  private static LinearLayout form(Activity a) {
    LinearLayout box = new LinearLayout(a);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0);
    return box;
  }

  /** A port between 1024 and 65535, or -1. */
  private static int parsePort(String text) {
    try {
      int n = Integer.parseInt(text.trim());
      return n >= 1024 && n <= 65535 ? n : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** The name typed in the field, saved. Null (with a message) if it is empty. */
  private static String readName(Activity a, EditText field) {
    String n = field.getText().toString().trim();
    if (n.isEmpty()) {
      Toast.makeText(a, "Escribe tu nombre primero", Toast.LENGTH_SHORT).show();
      return null;
    }
    prefs(a).edit().putString(KEY_NAME, n).apply();
    return n;
  }

  /** Opens the Online menu over the given screen. */
  static void show(Activity a) {
    LinearLayout box = form(a);

    label(a, box, "Tu nombre");
    EditText name = new EditText(a);
    name.setSingleLine(true);
    name.setHint("Jugador");
    name.setFilters(new InputFilter[] { new InputFilter.LengthFilter(16) });
    name.setText(playerName(a));
    box.addView(name);

    label(a, box, "Servidores");
    LinearLayout list = new LinearLayout(a);
    list.setOrientation(LinearLayout.VERTICAL);
    box.addView(list);
    renderList(a, list, name);

    button(a, box, "Actualizar lista", () -> renderList(a, list, name));
    button(a, box, "Crear servidor", () -> {
      String n = readName(a, name);
      if (n != null) createServer(a, n);
    });
    button(a, box, "Unirse directo (IP)", () -> {
      if (readName(a, name) != null) joinDirect(a);
    });

    ScrollView scroll = new ScrollView(a);
    scroll.addView(box);
    new AlertDialog.Builder(a).setTitle("Online").setView(scroll).setNegativeButton("Cerrar", null).show();
  }

  /** Draws the servers found, or a note when there are none. */
  private static void renderList(Activity a, LinearLayout list, EditText name) {
    list.removeAllViews();
    if (found.isEmpty()) {
      TextView none = new TextView(a);
      none.setText("No hay servidores a la vista. Crea uno o \u00fanete directo con su IP.");
      none.setPadding(0, dp(a, 6), 0, dp(a, 6));
      list.addView(none);
      return;
    }
    for (Server s : found) {
      Button row = new Button(a);
      row.setAllCaps(false);
      row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      row.setText(s.name + "  \u00b7  " + s.host + ":" + s.port);
      row.setOnClickListener(v -> {
        if (readName(a, name) != null) connect(a, s.host, s.port);
      });
      list.addView(row);
    }
  }

  /** Create a server: its name and port, saved. The hosting itself comes in the next step. */
  private static void createServer(Activity a, String player) {
    SharedPreferences pr = prefs(a);
    LinearLayout box = form(a);

    label(a, box, "Nombre del servidor");
    EditText serverName = new EditText(a);
    serverName.setSingleLine(true);
    serverName.setFilters(new InputFilter[] { new InputFilter.LengthFilter(24) });
    serverName.setText(pr.getString(KEY_SERVER, "Partida de " + player));
    box.addView(serverName);

    label(a, box, "Puerto");
    EditText port = new EditText(a);
    port.setSingleLine(true);
    port.setInputType(InputType.TYPE_CLASS_NUMBER);
    port.setText(String.valueOf(pr.getInt(KEY_PORT, DEFAULT_PORT)));
    box.addView(port);

    new AlertDialog.Builder(a).setTitle("Crear servidor").setView(box)
        .setPositiveButton("Crear", (d, w) -> {
          String sn = serverName.getText().toString().trim();
          if (sn.isEmpty()) sn = "Partida de " + player;
          int pn = parsePort(port.getText().toString());
          if (pn < 0) {
            Toast.makeText(a, "Puerto no v\u00e1lido (1024 a 65535)", Toast.LENGTH_LONG).show();
            return;
          }
          pr.edit().putString(KEY_SERVER, sn).putInt(KEY_PORT, pn).apply();
          Toast.makeText(a, "Servidor \"" + sn + "\" listo en el puerto " + pn + " (todav\u00eda sin red)", Toast.LENGTH_LONG).show();
        })
        .setNegativeButton(android.R.string.cancel, null).show();
  }

  /** Join directly: an address like 192.168.1.20:7777 (the port is optional). The connection comes in the next step. */
  private static void joinDirect(Activity a) {
    SharedPreferences pr = prefs(a);
    LinearLayout box = form(a);

    label(a, box, "IP y puerto del servidor");
    EditText addr = new EditText(a);
    addr.setSingleLine(true);
    addr.setHint("192.168.1.20:" + DEFAULT_PORT);
    addr.setText(pr.getString(KEY_LAST, ""));
    box.addView(addr);

    new AlertDialog.Builder(a).setTitle("Unirse directo").setView(box)
        .setPositiveButton("Unirse", (d, w) -> {
          String t = addr.getText().toString().trim();
          String host = t;
          int port = DEFAULT_PORT;
          int i = t.lastIndexOf(':');
          if (i >= 0) {
            host = t.substring(0, i).trim();
            port = parsePort(t.substring(i + 1));
          }
          if (host.isEmpty() || port < 0) {
            Toast.makeText(a, "Direcci\u00f3n no v\u00e1lida. Ejemplo: 192.168.1.20:" + DEFAULT_PORT, Toast.LENGTH_LONG).show();
            return;
          }
          pr.edit().putString(KEY_LAST, host + ":" + port).apply();
          connect(a, host, port);
        })
        .setNegativeButton(android.R.string.cancel, null).show();
  }

  /** Placeholder: the real connection is built in the next step. */
  private static void connect(Activity a, String host, int port) {
    Toast.makeText(a, "Conectar a " + host + ":" + port + " (todav\u00eda sin red)", Toast.LENGTH_LONG).show();
  }
}

package com.ast.re4decomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.net.BindException;
import java.util.ArrayList;
import java.util.List;

/** The "Online" menu of the start screen: player name, list of servers on the same Wi-Fi, create a server, join one
 *  directly. Since 25.11.2026 the network works (OnlineNet): a lobby with the player list and a chat. The game's own
 *  state is not sent yet. */
final class OnlineMenu {
  private static final String PREFS = "online";
  private static final String KEY_NAME = "name", KEY_SERVER = "serverName", KEY_PORT = "port", KEY_LAST = "lastAddress";
  static final int DEFAULT_PORT = 7777;

  /** The servers found so far (the search runs on "Actualizar lista"). */
  private static final List<OnlineNet.Found> found = new ArrayList<>();

  /** Makes the host's or the guest's side of a lobby; runs off the main thread. */
  private interface Maker {
    OnlineNet.Session make(OnlineNet.Listener listener) throws IOException;
  }

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

    button(a, box, "Actualizar lista", () -> refresh(a, list, name));
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
    for (OnlineNet.Found s : found) {
      Button row = new Button(a);
      row.setAllCaps(false);
      row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      row.setText(s.name + "  \u00b7  " + s.host + ":" + s.port + "  \u00b7  " + s.players + "/" + OnlineNet.MAX_PLAYERS);
      row.setOnClickListener(v -> {
        if (readName(a, name) != null) connect(a, s.host, s.port);
      });
      list.addView(row);
    }
  }

  /** Looks for servers on the same network (the default port and the saved one), then draws the list again. */
  private static void refresh(Activity a, LinearLayout list, EditText name) {
    Toast.makeText(a, "Buscando servidores…", Toast.LENGTH_SHORT).show();
    final int saved = prefs(a).getInt(KEY_PORT, DEFAULT_PORT);
    Thread t = new Thread(() -> {
      List<OnlineNet.Found> all = new ArrayList<>(OnlineNet.search(DEFAULT_PORT, 1200));
      if (saved != DEFAULT_PORT) {
        for (OnlineNet.Found f : OnlineNet.search(saved, 1200)) {
          boolean dup = false;
          for (OnlineNet.Found o : all) if (o.host.equals(f.host) && o.port == f.port) dup = true;
          if (!dup) all.add(f);
        }
      }
      a.runOnUiThread(() -> {
        found.clear();
        found.addAll(all);
        renderList(a, list, name);
      });
    }, "online-search");
    t.setDaemon(true);
    t.start();
  }

  /** The lobby window: players, chat, "Enviar" and "Salir". `maker` opens the connection off the main thread; the
   *  window appears once it worked, a message tells why when not. Closing the window leaves the lobby. */
  private static void openLobby(Activity a, String title, Maker maker) {
    LinearLayout box = form(a);
    label(a, box, "Jugadores");
    TextView players = new TextView(a);
    players.setTextSize(15);
    box.addView(players);
    label(a, box, "Chat");
    TextView chat = new TextView(a);
    chat.setTextSize(14);
    ScrollView chatScroll = new ScrollView(a);
    chatScroll.addView(chat);
    box.addView(chatScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 160)));
    EditText input = new EditText(a);
    input.setSingleLine(true);
    input.setHint("Escribe un mensaje");
    input.setImeOptions(EditorInfo.IME_ACTION_SEND);
    box.addView(input);

    final OnlineNet.Session[] session = new OnlineNet.Session[1];
    final AlertDialog[] dialog = new AlertDialog[1];
    final boolean[] ended = new boolean[1];
    Runnable send = () -> {
      String text = input.getText().toString().trim();
      if (session[0] != null && !text.isEmpty()) {
        session[0].say(text);
        input.setText("");
      }
    };
    input.setOnEditorActionListener((v, id, ev) -> { send.run(); return true; });
    button(a, box, "Enviar", send);

    OnlineNet.Listener listener = new OnlineNet.Listener() {
      @Override public void players(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append("• ").append(n).append('\n');
        a.runOnUiThread(() -> players.setText(sb.toString().trim()));
      }

      @Override public void message(String line) {
        a.runOnUiThread(() -> {
          chat.append(line + "\n");
          chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
        });
      }

      @Override public void closed(String why) {
        a.runOnUiThread(() -> {
          ended[0] = true;
          Toast.makeText(a, why, Toast.LENGTH_LONG).show();
          if (dialog[0] != null && dialog[0].isShowing()) dialog[0].dismiss();
        });
      }
    };

    Toast.makeText(a, "Conectando…", Toast.LENGTH_SHORT).show();
    Thread t = new Thread(() -> {
      try {
        OnlineNet.Session s = maker.make(listener);
        a.runOnUiThread(() -> {
          if (ended[0] || a.isFinishing() || a.isDestroyed()) { s.close(); return; }
          session[0] = s;
          AlertDialog d = new AlertDialog.Builder(a).setTitle(title).setView(box).setNegativeButton("Salir", null).create();
          d.setOnDismissListener(x -> s.close());
          dialog[0] = d;
          d.show();
        });
      } catch (IOException e) {
        String why = e instanceof BindException ? "Ese puerto ya está en uso. Prueba con otro."
            : e.getMessage() == null ? "No se pudo conectar" : e.getMessage();
        a.runOnUiThread(() -> Toast.makeText(a, why, Toast.LENGTH_LONG).show());
      }
    }, "online-open");
    t.setDaemon(true);
    t.start();
  }

  /** Create a server: its name and port, saved; then the lobby opens with this player as the host. */
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
          final String serverTitle = sn;
          final int serverPort = pn;
          openLobby(a, serverTitle + "  \u00b7  puerto " + serverPort,
              l -> new OnlineNet.Server(serverTitle, player, serverPort, l));
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

  /** Joins the lobby at host:port with the saved player name. */
  private static void connect(Activity a, String host, int port) {
    final String player = playerName(a);
    openLobby(a, host + ":" + port, l -> OnlineNet.Client.join(host, port, player, l));
  }
}

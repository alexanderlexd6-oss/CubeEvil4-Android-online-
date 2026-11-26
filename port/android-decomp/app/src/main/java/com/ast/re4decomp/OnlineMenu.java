package com.ast.re4decomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
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
 *  directly. Since 25.11.2026 the network works (OnlineNet): a lobby with the player list and a chat, and the host's
 *  "Empezar partida" opens the game for everybody (the room stays while the game runs). The game's own state is not
 *  sent yet. */
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

  private static final Handler UI = new Handler(Looper.getMainLooper());

  /** One lobby this device is in. It outlives its window and the start screen: it ends with "Salir de la sala", when
   *  the server closes, or when the app's process ends. */
  private static final class Room {
    final String title;
    volatile OnlineNet.Session session;
    volatile boolean ended;
    final StringBuilder chat = new StringBuilder();
    String players = "";
    // the screen and views of the window that is open now (null while none is)
    Activity act;
    AlertDialog dialog;
    TextView playersView, chatView;
    ScrollView chatScroll;
    Room(String title, Activity act) { this.title = title; this.act = act; }
  }

  private static volatile Room room;
  /** Given by "Online" on the start screen; "Empezar partida" runs it (the launcher's start of the game). */
  private static volatile Runnable startHook;

  /** This device is in a lobby: the game's menu then does not stop the game (CoreActivity.openMenu). */
  static boolean inRoom() {
    Room r = room;
    return r != null && !r.ended;
  }

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

  /** Opens the Online menu over the given screen: the lobby again when this device is in one. `startGame` starts the
   *  game (the start screen's button) when the host presses "Empezar partida". */
  static void show(Activity a, Runnable startGame) {
    startHook = startGame;
    if (inRoom()) {
      showLobby(a, room);
      return;
    }
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

  /** Closes the window of a lobby, if one is open (the lobby itself stays). */
  private static void closeWindow(Room r) {
    AlertDialog d = r.dialog;
    detach(r);
    if (d != null && d.isShowing()) d.dismiss();
  }

  private static void detach(Room r) {
    r.dialog = null;
    r.playersView = null;
    r.chatView = null;
    r.chatScroll = null;
  }

  /** Leaves the lobby for good ("Salir de la sala"). */
  private static void leave(Room r) {
    r.ended = true;
    if (room == r) room = null;
    OnlineNet.Session s = r.session;
    if (s != null) {
      Thread t = new Thread(s::close, "online-leave");
      t.setDaemon(true);
      t.start();
    }
  }

  /** Enters a lobby: `maker` opens the connection off the main thread; the window appears once it worked, a message
   *  tells why when not. */
  private static void openLobby(Activity a, String title, Maker maker) {
    if (inRoom()) {
      Toast.makeText(a, "Ya est\u00e1s en una sala. Sal de ella primero.", Toast.LENGTH_LONG).show();
      showLobby(a, room);
      return;
    }
    final Room r = new Room(title, a);
    OnlineNet.Listener listener = new OnlineNet.Listener() {
      @Override public void players(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append("\u2022 ").append(n).append('\n');
        final String text = sb.toString().trim();
        UI.post(() -> {
          r.players = text;
          if (r.playersView != null) r.playersView.setText(text);
        });
      }

      @Override public void message(String line) {
        UI.post(() -> {
          r.chat.append(line).append('\n');
          if (r.chatView != null) {
            r.chatView.setText(r.chat);
            r.chatScroll.post(() -> { if (r.chatScroll != null) r.chatScroll.fullScroll(View.FOCUS_DOWN); });
          }
        });
      }

      @Override public void closed(String why) {
        UI.post(() -> {
          r.ended = true;
          if (room == r) room = null;
          closeWindow(r);
          if (r.act != null) Toast.makeText(r.act, why, Toast.LENGTH_LONG).show();
        });
      }

      @Override public void started() {
        UI.post(() -> {
          closeWindow(r);
          Runnable go = startHook;
          if (go != null) go.run();
        });
      }
    };

    Toast.makeText(a, "Conectando\u2026", Toast.LENGTH_SHORT).show();
    Thread t = new Thread(() -> {
      try {
        OnlineNet.Session s = maker.make(listener);
        UI.post(() -> {
          if (r.ended) { s.close(); return; }
          r.session = s;
          room = r;
          if (!r.act.isFinishing() && !r.act.isDestroyed()) showLobby(r.act, r);
        });
      } catch (IOException e) {
        String why = e instanceof BindException ? "Ese puerto ya est\u00e1 en uso. Prueba con otro."
            : e.getMessage() == null ? "No se pudo conectar" : e.getMessage();
        UI.post(() -> Toast.makeText(a, why, Toast.LENGTH_LONG).show());
      }
    }, "online-open");
    t.setDaemon(true);
    t.start();
  }

  /** The lobby window: players, chat, "Enviar", the host's "Empezar partida", "Cerrar ventana" (the room stays) and
   *  "Salir de la sala". */
  private static void showLobby(Activity a, Room r) {
    closeWindow(r);
    r.act = a;
    LinearLayout box = form(a);
    label(a, box, "Jugadores");
    TextView players = new TextView(a);
    players.setTextSize(15);
    players.setText(r.players);
    box.addView(players);
    label(a, box, "Chat");
    TextView chat = new TextView(a);
    chat.setTextSize(14);
    chat.setText(r.chat);
    ScrollView chatScroll = new ScrollView(a);
    chatScroll.addView(chat);
    box.addView(chatScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 160)));
    EditText input = new EditText(a);
    input.setSingleLine(true);
    input.setHint("Escribe un mensaje");
    input.setImeOptions(EditorInfo.IME_ACTION_SEND);
    box.addView(input);
    Runnable send = () -> {
      String text = input.getText().toString().trim();
      OnlineNet.Session s = r.session;
      if (s != null && !text.isEmpty()) {
        s.say(text);
        input.setText("");
      }
    };
    input.setOnEditorActionListener((v, id, ev) -> { send.run(); return true; });
    button(a, box, "Enviar", send);
    if (r.session instanceof OnlineNet.Server) {
      button(a, box, "Empezar partida", () -> {
        OnlineNet.Session s = r.session;
        if (s != null) s.start();
      });
    } else {
      TextView wait = new TextView(a);
      wait.setText("Espera a que el anfitri\u00f3n empiece la partida.");
      wait.setPadding(0, dp(a, 10), 0, 0);
      box.addView(wait);
    }
    ScrollView scroll = new ScrollView(a);
    scroll.addView(box);
    AlertDialog d = new AlertDialog.Builder(a).setTitle(r.title).setView(scroll)
        .setPositiveButton("Cerrar ventana", null)
        .setNegativeButton("Salir de la sala", (x, w) -> leave(r))
        .create();
    r.dialog = d;
    r.playersView = players;
    r.chatView = chat;
    r.chatScroll = chatScroll;
    d.setOnDismissListener(x -> { if (r.dialog == d) detach(r); });
    d.show();
    chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
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

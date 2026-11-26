package com.ast.re4decomp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The network of the Online menu (25.11.2026): a lobby over TCP (player list and chat) and a search for servers on the
 * same Wi-Fi over UDP. No Android classes here, so it can be tested on a PC. Nothing in it touches the game yet: it
 * only connects the players; the game's own state is not sent.
 *
 * Protocol (one UTF-8 line each; the fields of a line are split by a tab):
 *   client -> server: HELLO name | SAY text | BYE
 *   server -> client: WELCOME serverName assignedName | FULL | PLAYERS name1 name2 ... | MSG text
 *   search (UDP, to the port of the lobby): "CE4?" -> "CE4!" serverName tcpPort players
 * Every call that waits for the network (join, search) must run off the main thread; say() and close() may be called
 * from any thread (their writes happen on a thread of their own).
 */
final class OnlineNet {
  static final String FIND = "CE4?", FOUND = "CE4!";
  static final int MAX_PLAYERS = 4, NAME_MAX = 16;
  private static final int CONNECT_MS = 4000;

  /** Called from network threads: post to the main thread before touching views. */
  interface Listener {
    void players(List<String> names);
    void message(String line);
    /** The lobby ended (the server closed it, the connection broke, or it was full). */
    void closed(String why);
    /** The host started the game: everybody opens it now. */
    void started();
  }

  /** What both sides offer to the screen. */
  interface Session {
    void say(String text);
    /** The host starts the game for everybody (does nothing for a guest). */
    void start();
    void close();
  }

  /** A server found by the search. */
  static final class Found {
    final String name, host;
    final int port, players;
    Found(String name, String host, int port, int players) { this.name = name; this.host = host; this.port = port; this.players = players; }
  }

  private OnlineNet() {}

  /** A name without line breaks and tabs, at most NAME_MAX long; "Jugador" when nothing is left. */
  static String clean(String s, String fallback) {
    String t = s == null ? "" : s.replaceAll("[\\r\\n\\t]", " ").trim();
    if (t.length() > NAME_MAX) t = t.substring(0, NAME_MAX).trim();
    return t.isEmpty() ? fallback : t;
  }

  private static String cleanText(String s) {
    String t = s == null ? "" : s.replaceAll("[\\r\\n\\t]", " ").trim();
    return t.length() > 200 ? t.substring(0, 200) : t;
  }

  private static BufferedReader reader(Socket s) throws IOException {
    return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
  }

  private static PrintWriter writer(Socket s) throws IOException {
    return new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
  }

  // ---- search -----------------------------------------------------------------------------------------------

  /** Looks for servers on the local network (UDP broadcast on `port`) for `waitMs`. Blocks. */
  static List<Found> search(int port, int waitMs) {
    try {
      return search(port, waitMs, InetAddress.getByName("255.255.255.255"));
    } catch (IOException e) {
      return new ArrayList<>();
    }
  }

  /** The same towards one address (tests use the loopback). */
  static List<Found> search(int port, int waitMs, InetAddress target) {
    List<Found> out = new ArrayList<>();
    try (DatagramSocket sock = new DatagramSocket()) {
      sock.setBroadcast(true);
      byte[] ask = FIND.getBytes(StandardCharsets.UTF_8);
      sock.send(new DatagramPacket(ask, ask.length, target, port));
      long end = System.currentTimeMillis() + waitMs;
      byte[] buf = new byte[512];
      while (true) {
        long left = end - System.currentTimeMillis();
        if (left <= 0) break;
        sock.setSoTimeout((int) left);
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
          sock.receive(p);
        } catch (SocketTimeoutException e) {
          break;
        }
        String[] f = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).split("\t");
        if (f.length < 4 || !FOUND.equals(f[0])) continue;
        try {
          Found s = new Found(f[1], p.getAddress().getHostAddress(), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
          boolean dup = false;
          for (Found o : out) if (o.host.equals(s.host) && o.port == s.port) dup = true;
          if (!dup) out.add(s);
        } catch (NumberFormatException ignored) {
          // a stranger's packet
        }
      }
    } catch (IOException ignored) {
      // no network: an empty list
    }
    return out;
  }

  // ---- server -----------------------------------------------------------------------------------------------

  /** The lobby's host (also a player). */
  static final class Server implements Session {
    private final String serverName, hostPlayer;
    private final Listener listener;
    private final ServerSocket tcp;
    private final DatagramSocket udp;  // null when its port is taken: the lobby still works, only the search misses it
    private final List<Peer> peers = new CopyOnWriteArrayList<>();
    private final ExecutorService writes = Executors.newSingleThreadExecutor();
    private volatile boolean open = true;

    private final class Peer {
      final Socket sock;
      final PrintWriter out;
      volatile String name;
      Peer(Socket sock) throws IOException { this.sock = sock; this.out = writer(sock); }
      void send(String line) {
        try {
          writes.execute(() -> out.println(line));
        } catch (RuntimeException ignored) {
          // closed meanwhile
        }
      }
    }

    /** Opens the lobby on `port` (TCP) and answers searches on the same port number (UDP). */
    Server(String serverName, String hostPlayer, int port, Listener listener) throws IOException {
      this.serverName = clean(serverName, "Partida");
      this.hostPlayer = clean(hostPlayer, "Jugador");
      this.listener = listener;
      ServerSocket t = new ServerSocket();
      t.setReuseAddress(true);
      t.bind(new InetSocketAddress(port));
      tcp = t;
      DatagramSocket u = null;
      try {
        u = new DatagramSocket(null);
        u.setReuseAddress(true);
        u.bind(new InetSocketAddress(port));
      } catch (IOException e) {
        if (u != null) u.close();
        u = null;
      }
      udp = u;
      thread("online-accept", this::acceptLoop);
      if (udp != null) thread("online-answer", this::answerLoop);
      listener.players(names());
    }

    int port() { return tcp.getLocalPort(); }

    private void thread(String name, Runnable r) {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      t.start();
    }

    List<String> names() {
      List<String> n = new ArrayList<>();
      n.add(hostPlayer);
      for (Peer p : peers) if (p.name != null) n.add(p.name);
      return n;
    }

    private void answerLoop() {
      byte[] buf = new byte[64];
      while (open) {
        try {
          DatagramPacket p = new DatagramPacket(buf, buf.length);
          udp.receive(p);
          if (!FIND.equals(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8))) continue;
          byte[] reply = (FOUND + "\t" + serverName + "\t" + port() + "\t" + names().size()).getBytes(StandardCharsets.UTF_8);
          udp.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
        } catch (IOException e) {
          if (!open) return;
        }
      }
    }

    private void acceptLoop() {
      while (open) {
        try {
          Socket s = tcp.accept();
          thread("online-peer", () -> serve(s));
        } catch (IOException e) {
          if (!open) return;
        }
      }
    }

    private void serve(Socket s) {
      Peer peer = null;
      try {
        s.setSoTimeout(CONNECT_MS);
        BufferedReader in = reader(s);
        peer = new Peer(s);
        String hello = in.readLine();
        if (hello == null || !hello.startsWith("HELLO\t")) { s.close(); return; }
        if (names().size() >= MAX_PLAYERS) {
          peer.out.println("FULL");
          s.close();
          return;
        }
        String wanted = clean(hello.substring(6), "Jugador"), assigned = wanted;
        for (int n = 2; names().contains(assigned); n++) assigned = clean(wanted, "Jugador") + " (" + n + ")";
        peer.name = assigned;
        s.setSoTimeout(0);
        peers.add(peer);
        peer.send("WELCOME\t" + serverName + "\t" + assigned);
        announce("* " + assigned + " entró");
        sendPlayers();
        String line;
        while (open && (line = in.readLine()) != null) {
          if (line.startsWith("SAY\t")) {
            String text = cleanText(line.substring(4));
            if (!text.isEmpty()) announce(assigned + ": " + text);
          } else if (line.equals("BYE")) {
            break;
          }
        }
      } catch (IOException ignored) {
        // the player's connection broke
      } finally {
        try { s.close(); } catch (IOException ignored) { }
        if (peer != null && peers.remove(peer) && peer.name != null && open) {
          announce("* " + peer.name + " salió");
          sendPlayers();
        }
      }
    }

    /** A line for everybody, the host included. */
    private void announce(String text) {
      for (Peer p : peers) if (p.name != null) p.send("MSG\t" + text);
      listener.message(text);
    }

    private void sendPlayers() {
      List<String> n = names();
      StringBuilder sb = new StringBuilder("PLAYERS");
      for (String s : n) sb.append('\t').append(s);
      for (Peer p : peers) if (p.name != null) p.send(sb.toString());
      listener.players(n);
    }

    /** The host speaks. */
    @Override public void say(String text) {
      String t = cleanText(text);
      if (open && !t.isEmpty()) announce(hostPlayer + ": " + t);
    }

    /** The host starts the game: START to every guest, and the host's own screen is told too. */
    @Override public void start() {
      if (!open) return;
      for (Peer p : peers) if (p.name != null) p.send("START");
      listener.started();
    }

    @Override public void close() {
      if (!open) return;
      open = false;
      try { tcp.close(); } catch (IOException ignored) { }
      if (udp != null) udp.close();
      for (Peer p : peers) try { p.sock.close(); } catch (IOException ignored) { }
      writes.shutdown();
    }
  }

  // ---- client -----------------------------------------------------------------------------------------------

  /** A player in somebody's lobby. */
  static final class Client implements Session {
    private final Socket sock;
    private final PrintWriter out;
    private final Listener listener;
    private final ExecutorService writes = Executors.newSingleThreadExecutor();
    private volatile boolean open = true;
    /** The server's name and the name this player got (a number is added to a name that is taken). */
    final String serverName, myName;

    private Client(Socket sock, BufferedReader in, PrintWriter out, String serverName, String myName, Listener listener) {
      this.sock = sock; this.out = out; this.listener = listener; this.serverName = serverName; this.myName = myName;
      Thread t = new Thread(() -> readLoop(in), "online-client");
      t.setDaemon(true);
      t.start();
    }

    /** Connects and enters the lobby. Blocks; throws IOException with a message for the player. */
    static Client join(String host, int port, String name, Listener listener) throws IOException {
      Socket s = new Socket();
      try {
        s.connect(new InetSocketAddress(host, port), CONNECT_MS);
        s.setSoTimeout(CONNECT_MS);
        PrintWriter out = writer(s);
        BufferedReader in = reader(s);
        out.println("HELLO\t" + clean(name, "Jugador"));
        String answer = in.readLine();
        if ("FULL".equals(answer)) throw new IOException("La sala está llena");
        if (answer == null || !answer.startsWith("WELCOME\t")) throw new IOException("Ese servidor no responde como una sala de CubeEvil4");
        String[] f = answer.split("\t");
        if (f.length < 3) throw new IOException("Respuesta no válida del servidor");
        s.setSoTimeout(0);
        return new Client(s, in, out, f[1], f[2], listener);
      } catch (SocketTimeoutException e) {
        try { s.close(); } catch (IOException ignored) { }
        throw new IOException("El servidor tardó demasiado en responder");
      } catch (IOException e) {
        try { s.close(); } catch (IOException ignored) { }
        throw e;
      }
    }

    private void readLoop(BufferedReader in) {
      String why = "El servidor cerró la conexión";
      try {
        String line;
        while (open && (line = in.readLine()) != null) {
          if (line.startsWith("PLAYERS")) {
            String[] f = line.split("\t");
            List<String> n = new ArrayList<>();
            for (int i = 1; i < f.length; i++) n.add(f[i]);
            listener.players(n);
          } else if (line.startsWith("MSG\t")) {
            listener.message(line.substring(4));
          } else if (line.equals("START")) {
            listener.started();
          }
        }
      } catch (IOException e) {
        if (open) why = "Se perdió la conexión";
      }
      if (open) {
        open = false;
        try { sock.close(); } catch (IOException ignored) { }
        writes.shutdown();
        listener.closed(why);
      }
    }

    @Override public void say(String text) {
      String t = cleanText(text);
      if (!open || t.isEmpty()) return;
      try {
        writes.execute(() -> out.println("SAY\t" + t));
      } catch (RuntimeException ignored) {
        // closed meanwhile
      }
    }

    @Override public void start() {
      // only the host starts the game
    }

    @Override public void close() {
      if (!open) return;
      open = false;
      try {
        writes.execute(() -> { out.println("BYE"); try { sock.close(); } catch (IOException ignored) { } });
      } catch (RuntimeException ignored) {
        try { sock.close(); } catch (IOException e) { }
      }
      writes.shutdown();
    }
  }
}

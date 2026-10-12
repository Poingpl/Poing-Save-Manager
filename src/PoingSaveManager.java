import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * PoingSaveManager - game save manager / menedzer zapisow gier.
 * Handles single files AND whole folders, global hotkeys, and shared profiles (e.g. a Google Drive folder).
 * Obsluguje pojedyncze pliki ORAZ cale foldery, skroty globalne i wspolne profile (np. folder Dysku Google).
 */
public class PoingSaveManager {

    static final String APP = "PoingSaveManager", VERSION = "1.0.0";
    static final String MARKER = "psm-snapshot.meta", OLD_MARKER = ".psm_snapshot", META_EXT = ".psm-meta";
    // palette from the poing. website / paleta ze strony poing.
    static final Color BG = new Color(0x0D1015), PANEL = new Color(0x101318), PANEL2 = new Color(0x151A21),
            LINE = new Color(0x262B33), FG = new Color(0xF6F7F9), MUTED = new Color(0x9AA1AC), DIM = new Color(0x5B6371),
            GREEN = new Color(0x1B8A6B), ERR = new Color(0xD9662E), FRIEND = new Color(0x7DA2FF);
    static final Color ACCENT = new Color(0x2E62E8);
    static final String MONO = "Consolas";

    // ===================== language / jezyk =====================
    static String LANG = "en";                                                  // default language
    static final List<String[]> LANGS = new ArrayList<>();                      // {code, display name} - fills the language drop-down
    static final Map<String, Properties> BUNDLES = new HashMap<>();

    /**
     * Languages = files "lang/<code>.properties". Built-in ones are listed in /lang/languages.txt (inside the jar);
     * any "<code>.properties" dropped into %APPDATA%\PoingSaveManager\lang\ is picked up too (and overrides built-in keys).
     * Adding a language needs no code change: copy lang/en.properties, translate, set language.name.
     */
    static void loadLanguages() {
        LANGS.clear(); BUNDLES.clear(); List<String> codes = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(PoingSaveManager.class.getResourceAsStream("/lang/languages.txt"), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) { l = l.trim(); if (!l.isEmpty() && !l.startsWith("#")) codes.add(l); }
        } catch (Exception ignored) { }
        if (!codes.contains("en")) codes.add(0, "en");
        Path ext = CFG_FILE.getParent().resolve("lang");
        if (Files.isDirectory(ext)) try (Stream<Path> st = Files.list(ext)) {
            for (Path f : (Iterable<Path>) st::iterator) { String n = f.getFileName().toString();
                if (n.endsWith(".properties") && !codes.contains(n.substring(0, n.length() - 11))) codes.add(n.substring(0, n.length() - 11)); }
        } catch (IOException ignored) { }
        for (String c : codes) {
            Properties p = new Properties();
            try (InputStream in = PoingSaveManager.class.getResourceAsStream("/lang/" + c + ".properties")) { if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8)); } catch (Exception ignored) { }
            Path f = ext.resolve(c + ".properties");
            if (Files.isRegularFile(f)) try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); } catch (Exception ignored) { }
            if (p.isEmpty()) continue;
            BUNDLES.put(c, p); LANGS.add(new String[]{c, p.getProperty("language.name", c)});
        }
    }

    /** Translated text for a key: selected language, then English, then the key itself. */
    static String tr(String key) {
        Properties p = BUNDLES.get(LANG); String v = p == null ? null : p.getProperty(key);
        if (v == null) { Properties en = BUNDLES.get("en"); if (en != null) v = en.getProperty(key); }
        return v != null ? v : key;
    }

    static String[] langNames() { return LANGS.stream().map(a -> a[1]).toArray(String[]::new); }
    static int langIndex(String code) { for (int i = 0; i < LANGS.size(); i++) if (LANGS.get(i)[0].equals(code)) return i; return 0; }

    // ===================== config =====================
    static final Path CFG_FILE = defaultCfg();
    static final Properties CFG = new Properties();
    static String NICK = "";
    static final Set<String> MY_NICKS = new LinkedHashSet<>();

    static Path defaultCfg() {
        String o = System.getProperty("psm.config");
        if (o != null) return Paths.get(o);
        String ad = System.getenv("APPDATA");
        Path base = ad != null ? Paths.get(ad) : Paths.get(System.getProperty("user.home"), ".config");
        return base.resolve(APP).resolve("config.properties");
    }

    static void loadCfg() {
        if (!Files.exists(CFG_FILE) && System.getProperty("psm.config") == null) {      // migration from the old program names
            for (String oldName : new String[]{"PoingSaveMenager", "PoingSpeedrunMenager"}) {
                Path old = CFG_FILE.getParent().getParent().resolve(oldName).resolve("config.properties");
                try { if (Files.exists(old)) { Files.createDirectories(CFG_FILE.getParent()); Files.copy(old, CFG_FILE); break; } } catch (IOException ignored) { }
            }
        }
        try (Reader r = Files.newBufferedReader(CFG_FILE, StandardCharsets.UTF_8)) { CFG.load(r); } catch (IOException ignored) { }
        CFG.remove("accent");
        loadLanguages();
        LANG = CFG.getProperty("lang", "en"); if (!BUNDLES.containsKey(LANG)) LANG = "en";
        NICK = CFG.getProperty("nick", "");
        MY_NICKS.clear();
        for (String n : CFG.getProperty("nicks", "").split("\t")) if (!n.isEmpty()) MY_NICKS.add(n);
        if (!NICK.isEmpty()) MY_NICKS.add(NICK);
    }

    static synchronized void saveCfg() {
        try {
            Files.createDirectories(CFG_FILE.getParent());
            Path tmp = CFG_FILE.resolveSibling("config.properties.tmp");           // write + rename = never a half-written config
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { CFG.store(w, APP); }
            try { Files.move(tmp, CFG_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException e) { Files.move(tmp, CFG_FILE, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ignored) { }
    }

    static void setNick(String n) {
        NICK = n; MY_NICKS.add(n); CFG.setProperty("nick", n); CFG.setProperty("nicks", String.join("\t", MY_NICKS)); saveCfg();
    }

    static boolean isMine(String author) { return author.isEmpty() || MY_NICKS.stream().anyMatch(n -> n.equalsIgnoreCase(author)); }

    static String hex(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

    // ===================== model =====================
    static class Profile {
        String name; Path target, storage; boolean shared;
        Profile(String n, Path t, Path s, boolean sh) { name = n; target = t; storage = s; shared = sh; }
        public String toString() { return name; }
    }

    /** Snapshot metadata (author, note, time, file count) - lives in the snapshot's marker file or in a sidecar. */
    static class Meta { String author = "", note = ""; long time; int files = -1; long bytes = -1; }

    static class Entry {
        final Path path; final boolean group; Meta meta = new Meta(); long mtime;
        Entry(Path p, boolean g) { path = p; group = g; }
        String name() { return path.getFileName().toString(); }
    }

    static Meta newMeta(String note) { Meta m = new Meta(); m.author = NICK; m.note = note == null ? "" : note; m.time = System.currentTimeMillis(); return m; }

    static Path markerOf(Path dir) { Path m = dir.resolve(MARKER); return Files.exists(m) || !Files.exists(dir.resolve(OLD_MARKER)) ? m : dir.resolve(OLD_MARKER); }

    static Path metaFile(Path snap) { return Files.isDirectory(snap) ? markerOf(snap) : snap.resolveSibling(snap.getFileName() + META_EXT); }

    static Meta readMeta(Path snap) {
        Meta m = new Meta(); Path f = metaFile(snap);
        if (!Files.isRegularFile(f)) return m;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); } catch (IOException | IllegalArgumentException e) { return m; }
        m.author = p.getProperty("author", ""); m.note = p.getProperty("note", "");
        try { m.time = Long.parseLong(p.getProperty("time", "0")); m.files = Integer.parseInt(p.getProperty("files", "-1")); m.bytes = Long.parseLong(p.getProperty("bytes", "-1")); }
        catch (NumberFormatException ignored) { }
        return m;
    }

    static void writeMeta(Path f, Meta m) throws IOException {
        Properties p = new Properties();
        p.setProperty("author", m.author); p.setProperty("note", m.note); p.setProperty("time", String.valueOf(m.time));
        p.setProperty("files", String.valueOf(m.files)); p.setProperty("bytes", String.valueOf(m.bytes)); p.setProperty("app", APP + " " + VERSION);
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) { p.store(w, null); }
    }

    // ===================== file operations =====================
    static boolean isMarker(Path p) { String n = p.getFileName().toString(); return n.equals(MARKER) || n.equals(OLD_MARKER); }
    static boolean isHidden(Path p) { String n = p.getFileName().toString(); return n.startsWith(".") || n.endsWith(META_EXT); }
    static boolean isGroup(Path p) { return Files.isDirectory(p) && !Files.exists(p.resolve(MARKER)) && !Files.exists(p.resolve(OLD_MARKER)); }

    static void copyTree(Path src, Path dst, boolean skipMarker) throws IOException {
        if (Files.isDirectory(src)) {
            Files.createDirectories(dst);
            List<Path> kids; try (Stream<Path> s = Files.list(src)) { kids = s.collect(Collectors.toList()); }
            for (Path k : kids) {
                if (skipMarker && isMarker(k)) continue;
                copyTree(k, dst.resolve(k.getFileName().toString()), skipMarker);
            }
        } else {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                f.toFile().setWritable(true); Files.delete(f); return FileVisitResult.CONTINUE; }
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d); return FileVisitResult.CONTINUE; }
        });
    }

    static void clearDir(Path d) throws IOException {
        List<Path> kids; try (Stream<Path> s = Files.list(d)) { kids = s.collect(Collectors.toList()); }
        for (Path k : kids) deleteTree(k);
    }

    static List<Path> files(Path p) throws IOException {
        if (!Files.exists(p)) return Collections.emptyList();
        try (Stream<Path> s = Files.walk(p)) { return s.filter(Files::isRegularFile).collect(Collectors.toList()); }
    }

    static boolean isRO(Path p) {
        try { List<Path> f = files(p); return !f.isEmpty() && f.stream().noneMatch(Files::isWritable); }
        catch (IOException e) { return false; }
    }

    static void setRO(Path p, boolean ro) throws IOException { for (Path f : files(p)) f.toFile().setWritable(!ro); }

    static Path unique(Path dir, String name) {
        Path p = dir.resolve(name); int i = 2;
        while (Files.exists(p)) p = dir.resolve(name + " (" + (i++) + ")");
        return p;
    }

    static String clean(String n) { return n.replaceAll("[\\\\/:*?\"<>|\\t\\r\\n]", "_").trim(); }

    /**
     * Creates a snapshot of "src" (file or folder) at "dst". It is built under a hidden temp name and renamed at the end,
     * so a synced folder (Google Drive) never exposes a half-copied save to a friend.
     */
    static void createSnapshot(Path src, Path dst, Meta m) throws IOException {
        Path parent = dst.toAbsolutePath().getParent(); Files.createDirectories(parent);
        Path tmp = parent.resolve(".tmp-" + System.nanoTime());
        try {
            if (Files.isDirectory(src)) {
                copyTree(src, tmp, true);
                List<Path> fl = files(tmp); long b = 0; for (Path f : fl) b += Files.size(f);
                m.files = fl.size(); m.bytes = b;
                writeMeta(tmp.resolve(MARKER), m);
                Files.move(tmp, dst);
            } else {
                Files.copy(src, tmp, StandardCopyOption.COPY_ATTRIBUTES);
                m.files = 1; m.bytes = Files.size(tmp);
                writeMeta(dst.resolveSibling(dst.getFileName() + META_EXT), m);     // meta first, then the save appears
                Files.move(tmp, dst);
            }
        } catch (IOException e) {
            deleteTree(tmp); if (!Files.isDirectory(src)) Files.deleteIfExists(dst.resolveSibling(dst.getFileName() + META_EXT)); throw e;
        }
    }

    /** Checks that a (possibly still syncing) snapshot is complete. */
    static void verify(Path snap) throws IOException {
        Meta m = readMeta(snap); if (m.files < 0) return;
        List<Path> fl = Files.isDirectory(snap) ? files(snap).stream().filter(f -> !isMarker(f)).collect(Collectors.toList()) : Collections.singletonList(snap);
        long b = 0; for (Path f : fl) b += Files.size(f);
        if (fl.size() != m.files || b != m.bytes)
            throw new IOException(String.format(tr("this_save_is_still_syncing_d_of_d_files_wait"), fl.size(), m.files));
    }

    /** Writes a save (file or folder) into the game target. */
    static void apply(Path src, Path target) throws IOException {
        boolean ro = isRO(target);
        if (ro) setRO(target, false);
        if (Files.isDirectory(src)) {
            if (Files.exists(target) && !Files.isDirectory(target))
                throw new IOException(tr("this_save_is_a_folder_but_the_profile_target"));
            Files.createDirectories(target); clearDir(target); copyTree(src, target, true);
        } else {
            if (Files.isDirectory(target))
                throw new IOException(tr("this_save_is_a_file_but_the_profile_target_i"));
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (ro) setRO(target, true);
    }

    static void moveSidecar(Path from, Path to) throws IOException {
        Path a = from.resolveSibling(from.getFileName() + META_EXT);
        if (Files.exists(a)) Files.move(a, to.resolveSibling(to.getFileName() + META_EXT), StandardCopyOption.REPLACE_EXISTING);
    }

    static void renameEntry(Path from, Path to) throws IOException {
        try { Files.move(from, to); } catch (IOException e) { if (Files.isDirectory(from)) { copyTree(from, to, false); deleteTree(from); } else throw e; }
        if (!Files.isDirectory(to)) moveSidecar(from, to);
    }

    static void movePath(Path src, Path dstDir) throws IOException { renameEntry(src, unique(dstDir, src.getFileName().toString())); }

    static void deleteEntry(Path p) throws IOException {
        boolean file = !Files.isDirectory(p); deleteTree(p);
        if (file) Files.deleteIfExists(p.resolveSibling(p.getFileName() + META_EXT));
    }

    static void copyEntry(Path src, Path dst) throws IOException {
        copyTree(src, dst, false);
        if (!Files.isDirectory(src)) { Path a = src.resolveSibling(src.getFileName() + META_EXT); if (Files.exists(a)) Files.copy(a, dst.resolveSibling(dst.getFileName() + META_EXT), StandardCopyOption.REPLACE_EXISTING); }
        if (!isGroup(dst) && !NICK.isEmpty()) { Meta m = readMeta(dst); m.author = NICK; m.time = System.currentTimeMillis(); Files.createDirectories(metaFile(dst).getParent()); writeMeta(metaFile(dst), m); }
    }

    static boolean foreignInside(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            for (Path k : (Iterable<Path>) s::iterator) {
                if (isHidden(k)) continue;
                if (isGroup(k) ? foreignInside(k) : !isMine(readMeta(k).author)) return true;
            }
        } catch (IOException ignored) { }
        return false;
    }

    static void collect(Path dir, Set<String> out) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path k : (Iterable<Path>) s::iterator) { if (isHidden(k)) continue; if (isGroup(k)) collect(k, out); else out.add(k.toString()); }
        }
    }

    // ===================== look (dark theme from the website) =====================
    static void applyLangUi() {
        UIManager.put("OptionPane.yesButtonText", tr("yes")); UIManager.put("OptionPane.noButtonText", tr("no"));
        UIManager.put("OptionPane.okButtonText", "OK"); UIManager.put("OptionPane.cancelButtonText", tr("cancel"));
    }

    static void setupLaf() {
        UIManager.put("control", PANEL); UIManager.put("info", PANEL2);
        UIManager.put("nimbusBase", new Color(0x1A1D25)); UIManager.put("nimbusBlueGrey", new Color(0x2A2F3A));
        UIManager.put("nimbusLightBackground", PANEL2); UIManager.put("nimbusFocus", ACCENT);
        UIManager.put("nimbusSelectionBackground", new Color(0x1B2A5E)); UIManager.put("nimbusSelectedText", Color.WHITE);
        UIManager.put("nimbusDisabledText", MUTED); UIManager.put("nimbusAlertYellow", ACCENT);
        UIManager.put("nimbusOrange", ACCENT); UIManager.put("nimbusGreen", ACCENT); UIManager.put("nimbusInfoBlue", ACCENT);
        UIManager.put("nimbusRed", ERR); UIManager.put("text", FG); UIManager.put("menuText", FG);
        UIManager.put("defaultFont", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("OptionPane.background", PANEL); UIManager.put("Panel.background", PANEL);
        UIManager.put("OptionPane.messageForeground", FG);
        try {
            for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels())
                if ("Nimbus".equals(i.getName())) { UIManager.setLookAndFeel(i.getClassName()); break; }
        } catch (Exception ignored) { }
        applyLangUi();
    }

    static Icon icon(final int kind) {
        return new Icon() {
            public int getIconWidth() { return 20; }
            public int getIconHeight() { return 18; }
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.translate(x, y);
                if (kind == 2) {
                    g.setColor(new Color(0x9AA1AC)); g.fillRoundRect(4, 1, 12, 16, 4, 4);
                    g.setColor(ACCENT); g.fillRect(7, 6, 6, 2); g.fillRect(7, 10, 6, 2);
                } else {
                    Color base = kind == 1 ? ACCENT : new Color(0x3A4556);
                    g.setColor(base.darker()); g.fillRoundRect(1, 2, 8, 5, 3, 3);
                    g.setColor(base); g.fillRoundRect(1, 4, 18, 12, 4, 4);
                    if (kind == 1) { g.setColor(BG); int[] xs = {11, 7, 10, 9, 14, 11, 13}, ys = {6, 11, 11, 15, 9, 9, 6}; g.fillPolygon(xs, ys, 7); }
                }
                g.dispose();
            }
        };
    }

    /** Pill button like on the website: light (primary) or outlined, blue on hover. */
    static class Btn extends JButton {
        boolean primary, hover;
        Btn(String t, boolean primary) {
            super(t); this.primary = primary;
            setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false);
            setFont(getFont().deriveFont(Font.BOLD, 13f));
            setBorder(new EmptyBorder(9, 20, 9, 20)); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); } });
        }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean on = isEnabled(), h = hover && on; int w = getWidth(), ht = getHeight();
            if (primary) {
                g.setColor(!on ? new Color(0x1B2029) : h ? ACCENT : FG); g.fillRoundRect(0, 0, w, ht, ht, ht);
                setForeground(!on ? DIM : h ? Color.WHITE : BG);
            } else {
                g.setColor(!on ? new Color(0x1E232B) : h ? ACCENT : LINE); g.drawRoundRect(0, 0, w - 1, ht - 1, ht, ht);
                setForeground(!on ? DIM : FG);
            }
            g.dispose(); super.paintComponent(g0);
        }
    }

    /** Modern on/off switch (replaces the system checkbox). */
    static class Switch extends JToggleButton {
        boolean hover;
        Switch(String text) {
            super(text); setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
            setHorizontalAlignment(SwingConstants.LEFT); setBorder(new EmptyBorder(5, 54, 5, 4));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); } });
        }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean on = isSelected(), en = isEnabled(); int tw = 40, th = 22, y = (getHeight() - th) / 2;
            g.setColor(!en ? new Color(0x1B2029) : on ? (hover ? ACCENT.brighter() : ACCENT) : (hover ? new Color(0x333A46) : LINE));
            g.fillRoundRect(2, y, tw, th, th, th);
            g.setColor(!en ? DIM : on ? Color.WHITE : MUTED);
            int d = th - 6; g.fillOval(on ? 2 + tw - d - 3 : 5, y + 3, d, d);
            g.dispose(); setForeground(!en ? DIM : on ? FG : MUTED); super.paintComponent(g0);
        }
    }

    // ===================== application =====================
    /** Dark drop-down list in the style of the website (used for profiles and for the language). */
    static <T> void styleCombo(JComboBox<T> c) {
        c.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            protected JButton createArrowButton() {
                JButton b = new JButton("\u25BE"); b.setBorder(new EmptyBorder(0, 8, 0, 8)); b.setContentAreaFilled(false);
                b.setFocusPainted(false); b.setForeground(ACCENT); return b; }
            public void paintCurrentValueBackground(Graphics g, Rectangle r, boolean f) { g.setColor(PANEL2); g.fillRect(r.x, r.y, r.width, r.height); }
        });
        c.setBackground(PANEL2); c.setForeground(FG); c.setBorder(new LineBorder(LINE, 1, true)); c.setFont(c.getFont().deriveFont(Font.BOLD));
        c.setRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                JLabel lb = (JLabel) super.getListCellRendererComponent(l, v, i, sel, f);
                lb.setBackground(sel ? new Color(0x1B2A5E) : PANEL2); lb.setForeground(FG); lb.setBorder(new EmptyBorder(5, 8, 5, 8)); return lb; }
        });
    }

    interface Act { void run() throws Exception; }

    final JPanel root = new JPanel(new BorderLayout(0, 10));
    final List<Profile> profiles = new ArrayList<>();
    JComboBox<Profile> combo; JTree tree; DefaultTreeModel model; JTextField search;
    JLabel status = new JLabel(" "), info = new JLabel(" "); Switch roBox;
    Btn bImport, bLoad, bReplace, bUndo, bFolder, bRename, bDelete, bOpen, bSort;
    List<Path> clip = new ArrayList<>(); boolean clipCut, sortDate, adjusting;
    JFrame frame; TrayIcon tray; MenuItem trayShow, trayExit; javax.swing.Timer toastTimer; JWindow toastWin;
    volatile Profile active;

    PoingSaveManager() { loadProfiles(); loadHotkeys(); buildUi(); refreshAll(); }

    Profile cur() { return (Profile) combo.getSelectedItem(); }
    Path base() { return cur() == null ? null : cur().storage; }
    boolean sharedCur() { return cur() != null && cur().shared; }

    void loadProfiles() {
        int n = Integer.parseInt(CFG.getProperty("count", "0"));
        for (int i = 0; i < n; i++) {
            String nm = CFG.getProperty("p." + i + ".name"), tg = CFG.getProperty("p." + i + ".target"), st = CFG.getProperty("p." + i + ".storage");
            if (nm != null && tg != null && st != null) profiles.add(new Profile(nm, Paths.get(tg), Paths.get(st), "true".equals(CFG.getProperty("p." + i + ".shared"))));
        }
        sortDate = "true".equals(CFG.getProperty("sortDate"));
    }

    void saveProfiles() {
        CFG.keySet().removeIf(k -> k.toString().startsWith("p."));
        CFG.setProperty("count", String.valueOf(profiles.size()));
        for (int i = 0; i < profiles.size(); i++) {
            Profile p = profiles.get(i);
            CFG.setProperty("p." + i + ".name", p.name); CFG.setProperty("p." + i + ".target", p.target.toString());
            CFG.setProperty("p." + i + ".storage", p.storage.toString()); CFG.setProperty("p." + i + ".shared", String.valueOf(p.shared));
        }
        if (cur() != null) CFG.setProperty("selected", cur().name);
        CFG.setProperty("sortDate", String.valueOf(sortDate));
        saveCfg();
    }

    void guard(Act a) {
        try { a.run(); } catch (Exception ex) {
            String m = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            status(m, true); JOptionPane.showMessageDialog(root, m, APP, JOptionPane.ERROR_MESSAGE);
        }
    }

    void status(String s, boolean err) {
        final Color dot = err ? ERR : GREEN; status.setFont(new Font(MONO, Font.PLAIN, 12));
        status.setIcon(new Icon() { public int getIconWidth() { return 16; } public int getIconHeight() { return 8; }
            public void paintIcon(Component c, Graphics g0, int x, int y) { Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(dot); g.fillOval(x, y, 8, 8); g.dispose(); } });
        status.setForeground(err ? ERR : MUTED); status.setText(s);
    }

    // ---------- UI ----------
    void buildUi() {
        root.setBackground(BG); root.setBorder(new EmptyBorder(14, 16, 12, 16));

        JLabel title = new JLabel("<html><span style='font-size:22px'>poing<span style='color:" + hex(ACCENT) + "'>.</span></span>"
                + " &nbsp;<span style='font-family:" + MONO + ";font-size:11px;color:" + hex(ACCENT) + "'>SAVE MANAGER</span></html>");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f)); title.setForeground(FG);
        Btn bNick = new Btn("Nick: " + (NICK.isEmpty() ? "-" : NICK), false); bNick.addActionListener(e -> askNick(false));
        bNick.setToolTipText(tr("your_nickname_shown_to_friends_on_your_saves"));
        bSort = new Btn("", false); bSort.addActionListener(e -> { sortDate = !sortDate; saveProfiles(); refreshTree(); });
        Btn bKeys = new Btn(tr("settings"), false); bKeys.addActionListener(e -> editSettings());
        JComboBox<String> langBox = new JComboBox<>(langNames()); langBox.setPreferredSize(new Dimension(130, 34)); styleCombo(langBox);
        langBox.setSelectedIndex(langIndex(LANG)); langBox.setToolTipText(tr("language"));
        langBox.addActionListener(e -> { String l = LANGS.get(langBox.getSelectedIndex())[0]; if (!l.equals(LANG)) SwingUtilities.invokeLater(() -> setLang(l)); });
        JPanel hr = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); hr.setOpaque(false); hr.add(bNick); hr.add(bSort); hr.add(bKeys); hr.add(langBox);
        JPanel head = new JPanel(new BorderLayout()); head.setOpaque(false); head.add(title, BorderLayout.WEST); head.add(hr, BorderLayout.EAST);

        combo = new JComboBox<>(); combo.setPreferredSize(new Dimension(230, 34)); styleCombo(combo);
        combo.addActionListener(e -> { if (!adjusting) { collectUi(); saveProfiles(); refreshAll(); } });
        Btn bNew = new Btn(tr("new_profile"), false), bEdit = new Btn(tr("edit"), false), bDel = new Btn(tr("delete_profile"), false);
        bNew.addActionListener(e -> guard(() -> editProfile(null)));
        bEdit.addActionListener(e -> guard(() -> { if (cur() != null) editProfile(cur()); }));
        bDel.addActionListener(e -> guard(this::deleteProfile));
        JPanel pr = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); pr.setOpaque(false);
        pr.add(combo); pr.add(bNew); pr.add(bEdit); pr.add(bDel);
        info.setForeground(MUTED); info.setFont(new Font(MONO, Font.PLAIN, 12));
        roBox = new Switch(tr("target_read_only"));
        roBox.addActionListener(e -> guard(() -> { setRO(cur().target, roBox.isSelected());
            status(tr("read_only_attribute") + (roBox.isSelected() ? tr("on") : tr("off")), false); }));
        JPanel ir = new JPanel(new BorderLayout(10, 0)); ir.setOpaque(false); ir.add(info, BorderLayout.CENTER); ir.add(roBox, BorderLayout.EAST);
        JPanel north = new JPanel(); north.setOpaque(false); north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        head.setAlignmentX(0); pr.setAlignmentX(0); ir.setAlignmentX(0);
        north.add(head); north.add(Box.createVerticalStrut(10)); north.add(pr); north.add(Box.createVerticalStrut(8)); north.add(ir);

        search = new JTextField() {
            protected void paintComponent(Graphics g) { super.paintComponent(g);
                if (getText().isEmpty() && !isFocusOwner()) { Graphics2D g2 = (Graphics2D) g.create(); g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(DIM); g2.setFont(new Font(MONO, Font.PLAIN, 12)); g2.drawString(tr("search_saves"), 12, getHeight() / 2 + 4); g2.dispose(); } }
        };
        search.setPreferredSize(new Dimension(100, 34)); search.setCaretColor(ACCENT);
        search.setBackground(PANEL2); search.setForeground(FG);
        search.setBorder(new CompoundBorder(new LineBorder(LINE, 1, true), new EmptyBorder(0, 10, 0, 10)));
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshTree(); }
            public void removeUpdate(DocumentEvent e) { refreshTree(); }
            public void changedUpdate(DocumentEvent e) { }
        });
        tree = new JTree(new DefaultMutableTreeNode("root")); tree.setRootVisible(false); tree.setShowsRootHandles(true);
        tree.setRowHeight(30); tree.setBackground(PANEL2); tree.setOpaque(true); tree.setCellRenderer(new Rend());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> { updateButtons(); stateDirty(); });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            public void treeExpanded(TreeExpansionEvent e) { stateDirty(); }
            public void treeCollapsed(TreeExpansionEvent e) { stateDirty(); } });
        tree.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                    if (p != null && !entry(p).group) guard(() -> doLoad());
                }
            }
            public void mousePressed(MouseEvent e) { if (tree.getRowForLocation(e.getX(), e.getY()) < 0) tree.clearSelection(); }
        });
        setupKeys(); setupDnd();
        JScrollPane sp = new JScrollPane(tree); sp.setBorder(new LineBorder(LINE, 1, true)); sp.getViewport().setBackground(PANEL2);
        JPanel center = new JPanel(new BorderLayout(0, 8)); center.setOpaque(false);
        center.add(search, BorderLayout.NORTH); center.add(sp, BorderLayout.CENTER);

        bImport = new Btn(tr("import_from_game"), true); bLoad = new Btn(tr("load_into_game"), true);
        bReplace = new Btn(tr("replace_save"), false); bUndo = new Btn(tr("undo_last_load"), false);
        bFolder = new Btn(tr("new_group"), false); bRename = new Btn(tr("rename_f2"), false);
        bDelete = new Btn(tr("delete_del"), false); bOpen = new Btn(tr("open_folder"), false);
        bImport.addActionListener(e -> guard(this::doImport)); bLoad.addActionListener(e -> guard(this::doLoad));
        bReplace.addActionListener(e -> guard(this::doReplace)); bUndo.addActionListener(e -> guard(this::doUndo));
        bFolder.addActionListener(e -> guard(this::doNewGroup)); bRename.addActionListener(e -> guard(this::doRename));
        bDelete.addActionListener(e -> guard(this::doDelete)); bOpen.addActionListener(e -> guard(this::doOpen));
        JPanel acts = new JPanel(new GridLayout(0, 1, 0, 8)); acts.setOpaque(false);
        for (Btn b : new Btn[]{bImport, bLoad, bReplace, bUndo, bFolder, bRename, bDelete, bOpen}) acts.add(b);
        JPanel east = new JPanel(new BorderLayout()); east.setOpaque(false); east.setPreferredSize(new Dimension(230, 10));
        east.add(acts, BorderLayout.NORTH);

        status(" ", false); status.setBorder(new EmptyBorder(4, 2, 0, 0));
        JPanel body = new JPanel(new BorderLayout(14, 0)); body.setOpaque(false);
        body.add(center, BorderLayout.CENTER); body.add(east, BorderLayout.EAST);
        JLabel credit = new JLabel("<html>" + tr("created_by").replace("Poing", "<font color='" + hex(FRIEND) + "'>Poing</font>") + "</html>");
        credit.setFont(new Font(MONO, Font.PLAIN, 12)); credit.setForeground(MUTED); credit.setBorder(new EmptyBorder(4, 0, 0, 2));
        JPanel south = new JPanel(new BorderLayout()); south.setOpaque(false); south.add(status, BorderLayout.CENTER); south.add(credit, BorderLayout.EAST);
        root.add(north, BorderLayout.NORTH); root.add(body, BorderLayout.CENTER); root.add(south, BorderLayout.SOUTH);
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control F"), "find");
        root.getActionMap().put("find", new AbstractAction() { public void actionPerformed(ActionEvent e) { search.requestFocusInWindow(); } });
        updateSortText();
    }

    void updateSortText() { if (bSort != null) bSort.setText(sortDate ? tr("sort_date") : tr("sort_name")); }

    void setLang(String l) {
        LANG = l; CFG.setProperty("lang", l); saveCfg(); applyLangUi();
        root.removeAll(); buildUi(); refreshAll();
        if (trayShow != null) { trayShow.setLabel(tr("show_window")); trayExit.setLabel(tr("quit")); }
        root.revalidate(); root.repaint();
    }

    void bind(String key, Act a) {
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
        tree.getActionMap().put(key, new AbstractAction() { public void actionPerformed(ActionEvent e) { guard(a); } });
    }

    void setupKeys() {
        bind("F2", this::doRename); bind("DELETE", this::doDelete); bind("ENTER", this::doLoad);
        bind("control C", () -> { clip = selectedPaths(); clipCut = false; status(tr("copied") + clip.size(), false); });
        bind("control X", () -> { checkOwn(selected(), tr("cut")); clip = selectedPaths(); clipCut = true; status(tr("cut_2") + clip.size(), false); });
        bind("control V", this::doPaste);
    }

    void setupDnd() {
        try { tree.setDragEnabled(true); tree.setDropMode(DropMode.ON); } catch (HeadlessException ignored) { }
        tree.setTransferHandler(new TransferHandler() {
            public int getSourceActions(JComponent c) { return MOVE; }
            protected Transferable createTransferable(JComponent c) {
                List<Path> sel = selectedPaths(); if (sel.isEmpty()) return null;
                return new StringSelection(sel.stream().map(Path::toString).collect(Collectors.joining("\n")));
            }
            public boolean canImport(TransferSupport s) {
                return s.isDrop() && cur() != null && (s.isDataFlavorSupported(DataFlavor.stringFlavor) || s.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
            }
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                if (!canImport(s)) return false;
                try {
                    TreePath tp = ((JTree.DropLocation) s.getDropLocation()).getPath();
                    Path dest = tp == null ? base() : dirFor(entry(tp));
                    Transferable tr = s.getTransferable();
                    if (tr.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        for (File f : (List<File>) tr.getTransferData(DataFlavor.javaFileListFlavor)) createSnapshot(f.toPath(), unique(dest, f.getName()), newMeta(""));
                        status(tr("added_saves_from_explorer"), false);
                    } else {
                        List<Path> src = new ArrayList<>();
                        for (String l : ((String) tr.getTransferData(DataFlavor.stringFlavor)).split("\n")) src.add(Paths.get(l));
                        checkOwnPaths(src, tr("move")); moveAll(src, dest);
                    }
                    refreshTree(); return true;
                } catch (Exception ex) { status(String.valueOf(ex.getMessage()), true); return false; }
            }
        });
    }

    static class Rend extends JLabel implements TreeCellRenderer {
        boolean sel;
        Rend() { setOpaque(false); setBorder(new EmptyBorder(3, 6, 3, 10)); setIconTextGap(8); }
        public Component getTreeCellRendererComponent(JTree tr, Object v, boolean s, boolean e, boolean l, int r, boolean f) {
            Object o = ((DefaultMutableTreeNode) v).getUserObject(); sel = s;
            if (o instanceof Entry) {
                Entry en = (Entry) o; boolean dir = Files.isDirectory(en.path);
                setIcon(icon(en.group ? 0 : dir ? 1 : 2));
                StringBuilder b = new StringBuilder("<html>" + esc(en.name()));
                if (!en.group) {
                    String d = new SimpleDateFormat(tr("date_format")).format(new Date(en.meta.time > 0 ? en.meta.time : en.mtime));
                    b.append("  <font face='").append(MONO).append("' color='#9AA1AC'>");
                    if (!en.meta.author.isEmpty()) b.append("<font color='").append(isMine(en.meta.author) ? "#9AA1AC" : hex(FRIEND)).append("'>").append(esc(en.meta.author)).append("</font> · ");
                    b.append(d).append(" · ").append(dir ? "folder" : tr("file"));
                    if (!en.meta.note.isEmpty()) { String n = en.meta.note.length() > 40 ? en.meta.note.substring(0, 40) + "…" : en.meta.note; b.append(" · ").append(tr("quote_open")).append(esc(n)).append(tr("quote_close")); }
                    b.append("</font>");
                }
                setText(b.append("</html>").toString());
            }
            setForeground(s ? Color.WHITE : FG); return this;
        }
        static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
        protected void paintComponent(Graphics g0) {
            if (sel) { Graphics2D g = (Graphics2D) g0.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 70)); g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10); g.dispose(); }
            super.paintComponent(g0);
        }
    }

    // ---------- refreshing ----------
    void refreshAll() {
        adjusting = true;
        Profile sel = cur(); combo.removeAllItems();
        for (Profile p : profiles) combo.addItem(p);
        String want = sel != null ? sel.name : CFG.getProperty("selected");
        for (Profile p : profiles) if (p.name.equals(want)) combo.setSelectedItem(p);
        adjusting = false;
        Profile p = cur(); active = p;
        if (p == null) uiProfile = ""; else if (!p.name.equals(uiProfile)) { uiProfile = p.name; loadUi(p); }
        if (p == null) { info.setText(tr("no_profile_yet_create_one_with_new_profile")); roBox.setEnabled(false); roBox.setSelected(false); }
        else {
            String kind = Files.isDirectory(p.target) ? tr("folder") : Files.isRegularFile(p.target) ? tr("file_2") : tr("missing");
            info.setText("<html>" + tr("target") + ": <b>" + kind + "</b> &nbsp;" + Rend.esc(p.target.toString()) + (p.shared ? " &nbsp;<font color='" + hex(FRIEND) + "'>● " + tr("shared") + "</font>" : "") + "</html>");
            roBox.setEnabled(Files.exists(p.target)); roBox.setSelected(isRO(p.target));
        }
        refreshTree();
    }

    void refreshTree() {
        Set<String> open = new HashSet<>(), keep = selectedPaths().stream().map(Path::toString).collect(Collectors.toSet());
        Enumeration<TreePath> en = tree.getExpandedDescendants(new TreePath(tree.getModel().getRoot()));
        if (en != null) while (en.hasMoreElements()) { Object o = ((DefaultMutableTreeNode) en.nextElement().getLastPathComponent()).getUserObject(); if (o instanceof Entry) open.add(((Entry) o).path.toString()); }
        open.addAll(restoreOpen); keep.addAll(restoreSel);
        DefaultMutableTreeNode r = new DefaultMutableTreeNode("root");
        String f = search == null ? "" : search.getText().trim().toLowerCase();
        if (base() != null) try { Files.createDirectories(base()); build(r, base(), f); } catch (IOException e) { status(e.getMessage(), true); }
        model = new DefaultTreeModel(r); tree.setModel(model);
        Enumeration<?> all = r.depthFirstEnumeration();
        while (all.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) all.nextElement();
            if (n.getUserObject() instanceof Entry && n.getChildCount() > 0 && (!f.isEmpty() || open.contains(((Entry) n.getUserObject()).path.toString())))
                tree.expandPath(new TreePath(n.getPath()));
        }
        all = r.depthFirstEnumeration();
        while (all.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) all.nextElement();
            if (n.getUserObject() instanceof Entry && keep.contains(((Entry) n.getUserObject()).path.toString())) tree.addSelectionPath(new TreePath(n.getPath()));
        }
        if (r.getChildCount() > 0) { restoreOpen.clear(); restoreSel.clear(); }
        updateSortText(); updateButtons();
    }

    void build(DefaultMutableTreeNode parent, Path dir, String f) throws IOException {
        List<Entry> kids = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path k : (Iterable<Path>) s::iterator) {
                if (isHidden(k)) continue;
                Entry e = new Entry(k, isGroup(k));
                try { e.mtime = Files.getLastModifiedTime(k).toMillis(); } catch (IOException ignored) { }
                if (!e.group) e.meta = readMeta(k);
                kids.add(e);
            }
        }
        kids.sort((a, b) -> {
            if (a.group != b.group) return a.group ? -1 : 1;
            if (sortDate) { int c = Long.compare(b.meta.time > 0 ? b.meta.time : b.mtime, a.meta.time > 0 ? a.meta.time : a.mtime); if (c != 0) return c; }
            return a.name().compareToIgnoreCase(b.name());
        });
        for (Entry e : kids) {
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(e);
            boolean match = f.isEmpty() || e.name().toLowerCase().contains(f) || e.meta.author.toLowerCase().contains(f) || e.meta.note.toLowerCase().contains(f);
            if (e.group) { build(n, e.path, f); if (match || n.getChildCount() > 0) parent.add(n); }
            else if (match) parent.add(n);
        }
    }

    void updateButtons() {
        List<Entry> s = selected(); boolean one = s.size() == 1, snap = one && !s.get(0).group, has = cur() != null;
        bImport.setEnabled(has); bFolder.setEnabled(has); bOpen.setEnabled(has);
        bLoad.setEnabled(snap); bReplace.setEnabled(snap); bRename.setEnabled(one); bDelete.setEnabled(!s.isEmpty());
        bUndo.setEnabled(has && Files.exists(backupDir()));
    }

    // ---------- helpers ----------
    Entry entry(TreePath p) { Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject(); return o instanceof Entry ? (Entry) o : null; }

    List<Entry> selected() {
        List<Entry> l = new ArrayList<>(); TreePath[] ps = tree.getSelectionPaths();
        if (ps != null) for (TreePath p : ps) { Entry e = entry(p); if (e != null) l.add(e); }
        return l;
    }

    List<Path> selectedPaths() { return selected().stream().map(e -> e.path).collect(Collectors.toList()); }

    Path dirFor(Entry e) { return e == null ? base() : e.group ? e.path : e.path.getParent(); }

    Path destDir() { List<Entry> s = selected(); return s.isEmpty() ? base() : dirFor(s.get(0)); }

    /** Backups live outside the (possibly synced) storage folder. */
    Path backupDir() { return cur() == null ? Paths.get("?") : CFG_FILE.getParent().resolve("backup").resolve(clean(cur().name)); }

    void select(Path p) {
        Enumeration<?> e = ((DefaultMutableTreeNode) model.getRoot()).depthFirstEnumeration();
        while (e.hasMoreElements()) { DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
            if (n.getUserObject() instanceof Entry && ((Entry) n.getUserObject()).path.equals(p)) {
                TreePath tp = new TreePath(n.getPath()); tree.setSelectionPath(tp); tree.scrollPathToVisible(tp); return; } }
    }

    boolean confirm(String msg) {
        return JOptionPane.showConfirmDialog(root, msg, APP, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /** In a shared profile you cannot modify a friend's saves. */
    void checkOwn(List<Entry> es, String verb) throws IOException {
        if (!sharedCur()) return;
        for (Entry e : es) {
            boolean foreign = e.group ? foreignInside(e.path) : !isMine(e.meta.author);
            if (foreign) throw new IOException(String.format(tr("you_can_t_s_s_it_contains_a_save_by_another"),
                    verb, e.name(), e.group ? "" : " (" + e.meta.author + ")"));
        }
    }

    void checkOwnPaths(List<Path> ps, String verb) throws IOException {
        List<Entry> es = new ArrayList<>();
        for (Path p : ps) { Entry e = new Entry(p, isGroup(p)); if (!e.group) e.meta = readMeta(p); es.add(e); }
        checkOwn(es, verb);
    }

    /** Dialog with name + optional note. Returns {name, note} or null. */
    String[] askNameNote(String title, String defName) {
        JTextField n = new JTextField(defName, 28), no = new JTextField(28);
        JPanel f = new JPanel(new GridLayout(0, 1, 0, 4));
        f.add(new JLabel(tr("save_name"))); f.add(n);
        f.add(new JLabel(tr("note_optional_e_g_after_boss_3"))); f.add(no);
        if (JOptionPane.showConfirmDialog(root, f, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null;
        return new String[]{n.getText(), no.getText().trim()};
    }

    String defaultName() {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date());
        return (sharedCur() && !NICK.isEmpty() ? NICK + " " : tr("save")) + ts;
    }

    /** Asks for the nickname. On first run the field is empty and a nickname is required (closing the dialog quits). */
    void askNick(boolean first) {
        JTextField tf = new JTextField(first ? "" : NICK, 24); tf.setBackground(PANEL2); tf.setForeground(FG); tf.setCaretColor(ACCENT);
        tf.setBorder(new CompoundBorder(new LineBorder(LINE, 1, true), new EmptyBorder(6, 10, 6, 10)));
        tf.addAncestorListener(new AncestorListener() {
            public void ancestorAdded(AncestorEvent e) { tf.requestFocusInWindow(); }
            public void ancestorRemoved(AncestorEvent e) { }
            public void ancestorMoved(AncestorEvent e) { }
        });
        JLabel err = new JLabel(" "); err.setForeground(ERR);
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 6));
        p.add(new JLabel(first ? tr("enter_your_nickname_friends_will_see_it_on_t")
                : tr("new_nickname_existing_saves_keep_the_old_nic")));
        p.add(tf); p.add(err);
        while (true) {
            int r = first ? JOptionPane.showOptionDialog(root, p, "Nick", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[]{"OK"}, "OK")
                    : JOptionPane.showConfirmDialog(root, p, "Nick", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (first ? r != 0 : r != JOptionPane.OK_OPTION) { if (first) exitApp(); return; }
            String n = clean(tf.getText()); if (n.length() > 24) n = n.substring(0, 24);
            if (n.isEmpty()) { err.setText(tr("the_nickname_can_t_be_empty")); continue; }
            setNick(n); break;
        }
        setLang(LANG);      // rebuild the header (shows the new nick)
    }

    // ---------- actions ----------
    void doImport() throws Exception {
        Profile p = cur(); if (p == null) return;
        if (!Files.exists(p.target)) throw new IOException(tr("profile_target_not_found") + p.target);
        String[] r = askNameNote(tr("import_save"), defaultName());
        if (r == null || clean(r[0]).isEmpty()) return;
        Path dst = unique(destDir(), clean(r[0])); createSnapshot(p.target, dst, newMeta(r[1]));
        refreshTree(); select(dst); status(tr("imported") + dst.getFileName(), false);
    }

    void doLoad() throws Exception {
        List<Entry> s = selected(); Profile p = cur();
        if (p == null || s.size() != 1 || s.get(0).group) return;
        Entry e = s.get(0); verify(e.path);
        if (!confirm(String.format(tr("load_save_s_the_current_game_s_will_be_overw"), e.name(), Files.isDirectory(p.target) ? tr("folder_2") : tr("file"))))
            return;
        loadEntry(e.path, p); status(tr("loaded") + e.name(), false);
    }

    /** Loads a save into the game with a backup (no prompts - also used by global hotkeys). */
    void loadEntry(Path snap, Profile p) throws IOException {
        verify(snap);
        if (Files.exists(p.target)) { deleteTree(backupDir()); Files.createDirectories(backupDir()); copyTree(p.target, backupDir().resolve(p.target.getFileName().toString()), false); }
        apply(snap, p.target); updateButtons();
    }

    void doUndo() throws Exception {
        Profile p = cur(); Path bk = backupDir().resolve(p.target.getFileName().toString());
        if (!Files.exists(bk)) throw new IOException(tr("no_backup_available"));
        if (!confirm(tr("restore_the_state_from_before_the_last_load"))) return;
        apply(bk, p.target); status(tr("restored_the_state_from_before_the_last_load"), false);
    }

    void doReplace() throws Exception {
        List<Entry> s = selected(); Profile p = cur();
        if (p == null || s.size() != 1 || s.get(0).group) return;
        if (!Files.exists(p.target)) throw new IOException(tr("profile_target_not_found") + p.target);
        checkOwn(s, tr("replace"));
        Path old = s.get(0).path;
        if (!confirm(String.format(tr("replace_save_s_with_the_current_game_state"), old.getFileName()))) return;
        Meta m = newMeta(s.get(0).meta.note);
        Path nw = old.resolveSibling("." + old.getFileName() + ".new");
        deleteEntry(nw); createSnapshot(p.target, nw, m); deleteEntry(old); renameEntry(nw, old);
        refreshTree(); select(old); status(tr("replaced_save") + old.getFileName(), false);
    }

    void doNewGroup() throws Exception {
        String n = (String) JOptionPane.showInputDialog(root, tr("new_group_name_a_folder_for_saves"), APP, JOptionPane.PLAIN_MESSAGE, null, null, tr("new_group"));
        if (n == null || clean(n).isEmpty()) return;
        Path d = unique(destDir(), clean(n)); Files.createDirectories(d); refreshTree(); select(d);
    }

    void doRename() throws Exception {
        List<Entry> s = selected(); if (s.size() != 1) return; Path o = s.get(0).path;
        checkOwn(s, tr("rename"));
        String n = (String) JOptionPane.showInputDialog(root, tr("new_name"), APP, JOptionPane.PLAIN_MESSAGE, null, null, o.getFileName().toString());
        if (n == null || clean(n).isEmpty() || clean(n).equals(o.getFileName().toString())) return;
        Path d = o.resolveSibling(clean(n));
        if (Files.exists(d)) throw new IOException(tr("an_item_with_that_name_already_exists"));
        renameEntry(o, d); refreshTree(); select(d);
    }

    void doDelete() throws Exception {
        List<Entry> es = selected(); if (es.isEmpty()) return;
        checkOwn(es, tr("delete"));
        if (!confirm(es.size() == 1 ? String.format(tr("delete_s"), es.get(0).name()) : String.format(tr("delete_the_selected_items_d"), es.size()))) return;
        for (Entry e : es) deleteEntry(e.path);
        refreshTree(); status(tr("deleted") + es.size(), false);
    }

    void doOpen() throws Exception { Files.createDirectories(destDir()); Desktop.getDesktop().open(destDir().toFile()); }

    void doPaste() throws Exception {
        if (clip.isEmpty() || cur() == null) return;
        Path dest = destDir();
        for (Path src : clip) {
            if (!Files.exists(src) || dest.startsWith(src)) continue;
            if (clipCut) { checkOwnPaths(Collections.singletonList(src), tr("move")); movePath(src, dest); }
            else copyEntry(src, unique(dest, src.getFileName().toString()));
        }
        if (clipCut) clip.clear();
        refreshTree(); status(tr("pasted"), false);
    }

    void moveAll(List<Path> src, Path dest) throws IOException {
        for (Path s : src) if (Files.exists(s) && !dest.startsWith(s) && !s.getParent().equals(dest)) movePath(s, dest);
        status(tr("moved"), false);
    }

    // ---------- profiles ----------
    void editProfile(Profile old) throws Exception {
        JTextField name = new JTextField(old == null ? "" : old.name, 28), target = new JTextField(old == null ? "" : old.target.toString(), 28),
                store = new JTextField(old == null ? "" : old.storage.toString(), 28);
        Btn bf = new Btn(tr("file_3"), false), bd = new Btn(tr("folder_3"), false), bs = new Btn(tr("folder_3"), false);
        bf.addActionListener(e -> choose(target, false, tr("choose_the_game_save_file")));
        bd.addActionListener(e -> choose(target, true, tr("choose_the_game_save_folder")));
        bs.addActionListener(e -> choose(store, true, tr("choose_the_folder_for_organizer_saves")));
        JPanel tp = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)); tp.setOpaque(false); tp.add(target); tp.add(bf); tp.add(bd);
        JPanel sp = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)); sp.setOpaque(false); sp.add(store); sp.add(bs);
        Switch shared = new Switch(tr("shared_profile_folder_synced_with_a_friend_e"));
        shared.setSelected(old != null && old.shared);
        JLabel hint = new JLabel("<html><span style='color:#9AA1AC'>" + tr("to_share_saves_pick_a_folder_on_google_drive") + "</span></html>");
        JPanel f = new JPanel(new GridLayout(0, 1, 0, 4));
        f.add(new JLabel(tr("profile_name_e_g_the_game_s_name"))); f.add(name);
        f.add(new JLabel(tr("game_save_to_manage_a_file_or_a_whole_folder"))); f.add(tp);
        f.add(new JLabel(tr("where_to_keep_the_saves_organizer"))); f.add(sp);
        f.add(shared); f.add(hint);
        while (true) {
            if (JOptionPane.showConfirmDialog(root, f, old == null ? tr("new_profile") : tr("edit_profile"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String err = null; Path tg = null, st = null;
            try { tg = Paths.get(target.getText().trim()).toAbsolutePath().normalize(); st = Paths.get(store.getText().trim()).toAbsolutePath().normalize(); } catch (Exception ex) { err = tr("invalid_path"); }
            if (err == null && name.getText().trim().isEmpty()) err = tr("enter_a_profile_name");
            if (err == null && (target.getText().trim().isEmpty() || store.getText().trim().isEmpty())) err = tr("fill_in_both_paths");
            if (err == null && (tg.getNameCount() < 2 || tg.equals(Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize())))
                err = tr("the_target_can_t_be_a_drive_or_your_home_fol");
            if (err == null && (st.startsWith(tg) || tg.startsWith(st))) err = tr("the_organizer_folder_and_the_game_target_can");
            if (err == null) for (Profile o : profiles) if (o != old && o.name.equalsIgnoreCase(name.getText().trim())) err = tr("a_profile_with_that_name_already_exists");
            if (err != null) { JOptionPane.showMessageDialog(root, err, APP, JOptionPane.ERROR_MESSAGE); continue; }
            Files.createDirectories(st);
            if (old == null) { Profile n = new Profile(name.getText().trim(), tg, st, shared.isSelected()); profiles.add(n); CFG.setProperty("selected", n.name); }
            else { old.name = name.getText().trim(); old.target = tg; old.storage = st; old.shared = shared.isSelected(); CFG.setProperty("selected", old.name); }
            saveProfiles(); refreshAll(); return;
        }
    }

    /** File/folder picker: the native Windows dialog (via the launcher), with fallbacks. */
    void choose(JTextField f, boolean folder, String title) {
        String init = f.getText().trim(), res = null;
        if (BRIDGE) res = nativePick(folder, title, init);
        else if (!folder) {
            FileDialog fd = new FileDialog((Frame) SwingUtilities.getWindowAncestor(root), title, FileDialog.LOAD);
            Path ip = init.isEmpty() ? null : Paths.get(init).getParent(); if (ip != null && Files.isDirectory(ip)) fd.setDirectory(ip.toString());
            fd.setVisible(true); if (fd.getFile() != null) res = Paths.get(fd.getDirectory(), fd.getFile()).toString();
        } else {
            JFileChooser c = new JFileChooser(init.isEmpty() ? null : new File(init)); c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); c.setDialogTitle(title);
            if (c.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) res = c.getSelectedFile().getAbsolutePath();
        }
        if (res != null && !res.isEmpty()) f.setText(res);
    }

    String nativePick(boolean folder, String title, String init) {
        CompletableFuture<String> fut = new CompletableFuture<>(); pending = fut;
        out().println("PICK " + (folder ? "folder" : "file") + "\t" + init.replace('\t', ' ').replace('\n', ' ') + "\t" + title); out().flush();
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        Thread w = new Thread(() -> { try { Thread.sleep(80); fut.get(30, TimeUnit.MINUTES); } catch (Exception ignored) { } loop.exit(); }, "psm-pick");
        w.setDaemon(true); w.start(); loop.enter();
        try { return fut.getNow(null); } finally { pending = null; }
    }

    void deleteProfile() throws Exception {
        Profile p = cur(); if (p == null) return;
        if (!confirm(String.format(tr("remove_profile_s_from_the_list_saves_in_the"), p.name))) return;
        profiles.remove(p); CFG.remove("selected"); CFG.keySet().removeIf(k -> k.toString().startsWith("ui." + p.name + ".")); saveProfiles(); refreshAll();
    }

    // ---------- friend watcher (shared folders) ----------
    final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> { Thread th = new Thread(r, "psm-watch"); th.setDaemon(true); return th; });
    String knownKey = ""; Set<String> known = new HashSet<>(); final Map<String, Integer> pendingNew = new HashMap<>();

    void startWatcher() { sched.scheduleWithFixedDelay(() -> { try { scan(); } catch (Throwable ignored) { } }, 3, 5, TimeUnit.SECONDS); }

    void scan() throws IOException {
        Profile p = active; if (p == null) return;
        Set<String> now = new HashSet<>(); collect(p.storage, now);
        String key = p.name + "|" + p.storage;
        if (!key.equals(knownKey)) { known = now; knownKey = key; pendingNew.clear(); return; }
        boolean changed = !now.equals(known);
        for (String s : now) if (!known.contains(s)) pendingNew.putIfAbsent(s, 0);
        pendingNew.keySet().retainAll(now); known = now;
        List<String> news = new ArrayList<>();
        for (Iterator<Map.Entry<String, Integer>> it = pendingNew.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Integer> e = it.next(); Path sp = Paths.get(e.getKey()); Meta m = readMeta(sp);
            int tries = e.getValue() + 1; e.setValue(tries);
            if (m.time > 0 || tries >= 3) { it.remove(); if (!isMine(m.author)) news.add(m.author + ": " + sp.getFileName()); }
        }
        if (changed || !news.isEmpty()) SwingUtilities.invokeLater(() -> {
            refreshTree();
            if (news.size() == 1) toast(tr("new_save_from") + news.get(0), false);
            else if (news.size() > 1) toast(tr("new_saves_from_friends") + news.size(), false);
        });
    }

    // ---------- global hotkeys ----------
    static final boolean BRIDGE = Boolean.getBoolean("psm.bridge");   // set by PoingSaveManager.exe
    static String hkName(int i) { return tr("hotkey_name_" + i); }
    final int[][] hk = new int[5][2];                                  // {WinAPI modifiers, VK code}; VK=0 -> none

    void loadHotkeys() {
        for (int i = 0; i < hk.length; i++) {
            String v = CFG.getProperty("hk." + i);
            if (v != null && v.contains(",")) try { hk[i][0] = Integer.parseInt(v.split(",")[0]); hk[i][1] = Integer.parseInt(v.split(",")[1]); } catch (NumberFormatException ignored) { }
            if (hkReserved(hk[i][0], hk[i][1])) { hk[i][0] = 0; hk[i][1] = 0; }
        }
    }

    void saveHotkeys() { for (int i = 0; i < hk.length; i++) CFG.setProperty("hk." + i, hk[i][0] + "," + hk[i][1]); saveCfg(); sendHotkeys(); }

    boolean anyHotkey() { for (int[] h : hk) if (h[1] != 0) return true; return false; }

    static String hkText(int mods, int vk) {
        if (vk == 0) return tr("none");
        StringBuilder b = new StringBuilder();
        if ((mods & 2) != 0) b.append("Ctrl+"); if ((mods & 1) != 0) b.append("Alt+"); if ((mods & 4) != 0) b.append("Shift+");
        if (vk >= 0x70 && vk <= 0x87) b.append("F").append(vk - 0x6F);
        else if (vk >= 0x60 && vk <= 0x69) b.append("Num").append(vk - 0x60);
        else switch (vk) { case 0x20: b.append(tr("space")); break; case 0x21: b.append("PgUp"); break; case 0x22: b.append("PgDn"); break;
            case 0x23: b.append("End"); break; case 0x24: b.append("Home"); break; case 0x25: b.append("←"); break; case 0x26: b.append("↑"); break;
            case 0x27: b.append("→"); break; case 0x28: b.append("↓"); break; default: b.append((char) vk); }
        return b.toString();
    }

    /** AWT key codes match Windows VK codes only for this set of keys. */
    static boolean hkAllowed(int k) {
        return (k >= 'A' && k <= 'Z') || (k >= '0' && k <= '9') || (k >= 0x70 && k <= 0x87) || (k >= 0x60 && k <= 0x69) || (k >= 0x20 && k <= 0x28 && k != 0x2B);
    }

    /** Keys used inside the app (F2 = rename) and system shortcuts that must never be taken globally. */
    static boolean hkReserved(int mods, int vk) {
        if (vk == 0x71) return true;                                                        // F2 (rename in the app)
        if (mods == 2 && "CXVAZYF".indexOf(vk) >= 0 && vk >= 'A') return true;             // Ctrl+C/X/V/A/Z/Y/F
        return mods == 1 && vk == 0x73;                                                     // Alt+F4
    }

    static PrintStream out;   // channel to the launcher, always UTF-8
    static PrintStream out() {
        if (out == null) try { out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8"); } catch (UnsupportedEncodingException e) { out = System.out; }
        return out;
    }
    volatile CompletableFuture<String> pending;

    void startBridge() {
        if (!BRIDGE) return;
        Thread th = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("PICKED\t")) { if (pending != null) pending.complete(l.substring(7)); }
                    else if (l.startsWith("PICKCANCEL")) { if (pending != null) pending.complete(null); }
                    else if (l.startsWith("AUTOSTART_OK ")) { if (pendingAuto != null) pendingAuto.complete(l.substring(13).trim()); }
                    else if (l.startsWith("AUTOSTART_FAIL")) { if (pendingAuto != null) pendingAuto.complete(null); }
                    else if (l.startsWith("HOTKEY ")) onHotkey(Integer.parseInt(l.substring(7).trim()) - 1);
                    else if (l.startsWith("HKFAIL ")) { int i = Integer.parseInt(l.substring(7).trim()) - 1;
                        SwingUtilities.invokeLater(() -> toast(tr("hotkey_already_used_by_another_program") + hkName(i), true)); }
                }
            } catch (Exception ignored) { }
        }, "psm-bridge");
        th.setDaemon(true); th.start(); sendHotkeys();
    }

    void sendHotkeys() {
        if (!BRIDGE) return;
        out().println("HKCLEAR");
        for (int i = 0; i < hk.length; i++) if (hk[i][1] != 0) out().println("HK " + (i + 1) + " " + hk[i][0] + " " + hk[i][1]);
        out().flush();
    }

    void onHotkey(int i) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (cur() == null) return;
                switch (i) { case 0: quickImport(); break; case 1: quickLoadSelected(); break; case 2: loadRelative(-1); break;
                    case 3: loadRelative(1); break; case 4: quickUndo(); break; default: }
            } catch (Exception ex) { toast(tr("error") + (ex.getMessage() != null ? ex.getMessage() : ex), true); }
        });
    }

    void quickImport() throws Exception {
        Profile p = cur();
        if (!Files.exists(p.target)) throw new IOException(tr("profile_target_not_found_2"));
        Path dst = unique(destDir(), defaultName());
        createSnapshot(p.target, dst, newMeta("")); refreshTree(); select(dst); toast(tr("imported") + dst.getFileName(), false);
    }

    void quickLoadSelected() throws Exception {
        List<Entry> s = selected();
        if (s.size() != 1 || s.get(0).group) throw new IOException(tr("select_a_save_in_the_list"));
        loadEntry(s.get(0).path, cur()); toast(tr("loaded") + s.get(0).name(), false);
    }

    void loadRelative(int d) throws Exception {
        List<Path> snaps = new ArrayList<>();
        Enumeration<?> e = ((DefaultMutableTreeNode) model.getRoot()).preorderEnumeration();
        while (e.hasMoreElements()) { Object o = ((DefaultMutableTreeNode) e.nextElement()).getUserObject(); if (o instanceof Entry && !((Entry) o).group) snaps.add(((Entry) o).path); }
        if (snaps.isEmpty()) throw new IOException(tr("no_saves_in_the_list"));
        List<Entry> s = selected(); int idx = s.size() == 1 && !s.get(0).group ? snaps.indexOf(s.get(0).path) : -1;
        int n = idx < 0 ? (d > 0 ? 0 : snaps.size() - 1) : Math.floorMod(idx + d, snaps.size());
        select(snaps.get(n)); loadEntry(snaps.get(n), cur()); toast(tr("loaded") + snaps.get(n).getFileName(), false);
    }

    void quickUndo() throws Exception {
        Profile p = cur(); Path bk = backupDir().resolve(p.target.getFileName().toString());
        if (!Files.exists(bk)) throw new IOException(tr("no_backup_available_2"));
        apply(bk, p.target); toast(tr("restored_the_state_from_before_the_load"), false);
    }

    /** Small always-on-top notification that does not steal focus from the game. */
    void toast(String msg, boolean err) {
        status(msg, err);
        try {
            if (toastWin != null) toastWin.dispose();
            JWindow w = new JWindow(); w.setFocusableWindowState(false); w.setAlwaysOnTop(true);
            JLabel l = new JLabel(msg); l.setForeground(FG); l.setFont(new Font(MONO, Font.BOLD, 13));
            l.setBorder(new CompoundBorder(new LineBorder(err ? ERR : ACCENT, 2, true), new EmptyBorder(10, 16, 10, 16)));
            l.setOpaque(true); l.setBackground(BG); w.setContentPane(l); w.pack();
            Dimension sc = Toolkit.getDefaultToolkit().getScreenSize();
            w.setLocation(sc.width - w.getWidth() - 24, sc.height - w.getHeight() - 80); w.setVisible(true); toastWin = w;
            if (toastTimer != null) toastTimer.stop();
            toastTimer = new javax.swing.Timer(2500, ev -> w.dispose()); toastTimer.setRepeats(false); toastTimer.start();
        } catch (HeadlessException ignored) { }
    }

    static class HkField extends JTextField {
        int mods, vk;
        HkField(int m, int v) {
            mods = m; vk = v; setEditable(false); setFocusTraversalKeysEnabled(false); setText(hkText(m, v)); setColumns(16);
            setBackground(PANEL2); setForeground(FG); setCaretColor(PANEL2);
            addKeyListener(new KeyAdapter() {
                public void keyPressed(KeyEvent e) {
                    int k = e.getKeyCode();
                    if (k == KeyEvent.VK_SHIFT || k == KeyEvent.VK_CONTROL || k == KeyEvent.VK_ALT || k == KeyEvent.VK_META || k == KeyEvent.VK_ALT_GRAPH) return;
                    e.consume();
                    if (k == KeyEvent.VK_BACK_SPACE) { mods = 0; vk = 0; setText(hkText(0, 0)); return; }
                    int mm = (e.isControlDown() ? 2 : 0) | (e.isAltDown() ? 1 : 0) | (e.isShiftDown() ? 4 : 0);
                    boolean fOrNum = (k >= 0x70 && k <= 0x87) || (k >= 0x60 && k <= 0x69);
                    if (!hkAllowed(k)) { setText(tr("key_not_allowed")); return; }
                    if (hkReserved(mm, k)) { setText(k == 0x71 ? tr("f2_is_used_inside_the_app") : tr("reserved_system_shortcut")); return; }
                    if (mm == 0 && !fOrNum) { setText(tr("add_ctrl_alt_shift")); return; }
                    mods = mm; vk = k; setText(hkText(mm, k));
                }
            });
        }
    }

    static JLabel section(String text) {
        JLabel l = new JLabel(text.toUpperCase()); l.setFont(new Font(MONO, Font.BOLD, 11)); l.setForeground(ACCENT); l.setBorder(new EmptyBorder(8, 0, 2, 0)); return l;
    }

    void editSettings() {
        JPanel f = new JPanel(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(4, 4, 4, 4); c.anchor = GridBagConstraints.WEST;
        c.gridy = 0; c.gridx = 0; c.gridwidth = 2; f.add(section(tr("hotkeys")), c); c.gridwidth = 1;
        HkField[] fs = new HkField[hk.length];
        for (int i = 0; i < hk.length; i++) {
            c.gridy = i + 1; c.gridx = 0; f.add(new JLabel(hkName(i)), c);
            fs[i] = new HkField(hk[i][0], hk[i][1]); c.gridx = 1; f.add(fs[i], c);
        }
        c.gridy = hk.length + 1; c.gridx = 0; c.gridwidth = 2;
        f.add(new JLabel("<html><span style='color:#9AA1AC'>" + tr("click_a_field_and_press_a_combination_backsp")
                + (BRIDGE ? tr("hotkeys_work_globally_even_when_the_window_i")
                : "<b>" + tr("note") + "</b> " + tr("global_hotkeys_only_work_when_started_via_po")) + "</span></html>"), c);
        c.gridy = hk.length + 2; f.add(section(tr("startup")), c);
        Switch startup = new Switch(tr("start_with_windows_in_the_tray"));
        startup.setSelected(AUTOSTART); startup.setEnabled(BRIDGE);
        if (!BRIDGE) startup.setToolTipText(tr("available_when_started_via_poingsavemanager"));
        c.gridy = hk.length + 3; f.add(startup, c);
        if (JOptionPane.showConfirmDialog(root, f, tr("settings"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        Set<String> seen = new HashSet<>();
        for (HkField h : fs) if (h.vk != 0 && !seen.add(h.mods + "," + h.vk)) { JOptionPane.showMessageDialog(root, tr("the_same_hotkey_was_assigned_twice"), APP, JOptionPane.ERROR_MESSAGE); return; }
        for (int i = 0; i < hk.length; i++) { hk[i][0] = fs[i].mods; hk[i][1] = fs[i].vk; }
        saveHotkeys();
        if (BRIDGE && startup.isSelected() != AUTOSTART && !setAutostart(startup.isSelected()))
            JOptionPane.showMessageDialog(root, tr("could_not_change_the_startup_setting"), APP, JOptionPane.ERROR_MESSAGE);
        status(tr("settings_saved"), false);
    }

    // ---------- autostart (Windows registry, done by the launcher) ----------
    static volatile boolean AUTOSTART = Boolean.getBoolean("psm.autostart");
    volatile CompletableFuture<String> pendingAuto;

    boolean setAutostart(boolean on) {
        if (!BRIDGE) return false;
        CompletableFuture<String> fut = new CompletableFuture<>(); pendingAuto = fut;
        out().println("AUTOSTART SET " + (on ? 1 : 0)); out().flush();
        try { String r = fut.get(4, TimeUnit.SECONDS); if (on ? "1".equals(r) : "0".equals(r)) { AUTOSTART = on; return true; } }
        catch (Exception ignored) { } finally { pendingAuto = null; }
        return false;
    }

    // ---------- remembering the app state between runs ----------
    javax.swing.Timer stateTimer; String uiProfile = ""; final Set<String> restoreOpen = new HashSet<>(), restoreSel = new HashSet<>();

    void stateDirty() {
        if (stateTimer == null) { stateTimer = new javax.swing.Timer(700, e -> saveState()); stateTimer.setRepeats(false); }
        stateTimer.restart();
    }

    Profile profileByName(String n) { for (Profile p : profiles) if (p.name.equals(n)) return p; return null; }

    static String rel(Path base, Path p) { try { return base.relativize(p).toString(); } catch (IllegalArgumentException e) { return ""; } }

    /** Remembers which groups are open and which saves are selected (per profile). */
    void collectUi() {
        Profile p = profileByName(uiProfile);
        if (p == null || tree == null || search == null || !search.getText().trim().isEmpty()) return;
        if (((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildCount() == 0) return;      // e.g. folder not mounted yet - keep old state
        List<String> open = new ArrayList<>(), sel = new ArrayList<>();
        Enumeration<TreePath> en = tree.getExpandedDescendants(new TreePath(tree.getModel().getRoot()));
        if (en != null) while (en.hasMoreElements()) { Object o = ((DefaultMutableTreeNode) en.nextElement().getLastPathComponent()).getUserObject(); if (o instanceof Entry) open.add(rel(p.storage, ((Entry) o).path)); }
        for (Entry e : selected()) sel.add(rel(p.storage, e.path));
        CFG.setProperty("ui." + p.name + ".open", String.join("|", open)); CFG.setProperty("ui." + p.name + ".sel", String.join("|", sel));
    }

    void loadUi(Profile p) {
        restoreOpen.clear(); restoreSel.clear();
        for (String s : CFG.getProperty("ui." + p.name + ".open", "").split("\\|")) if (!s.isEmpty()) restoreOpen.add(p.storage.resolve(s).toString());
        for (String s : CFG.getProperty("ui." + p.name + ".sel", "").split("\\|")) if (!s.isEmpty()) restoreSel.add(p.storage.resolve(s).toString());
    }

    /** Writes everything worth remembering: profiles, selected profile, sort, open groups, selection, window position/size. */
    void saveState() {
        try {
            collectUi();
            if (frame != null) {
                int st = frame.getExtendedState(); boolean max = (st & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
                CFG.setProperty("win.max", String.valueOf(max));
                if (!max && (st & JFrame.ICONIFIED) == 0) {
                    Rectangle b = frame.getBounds();
                    CFG.setProperty("win.x", String.valueOf(b.x)); CFG.setProperty("win.y", String.valueOf(b.y));
                    CFG.setProperty("win.w", String.valueOf(b.width)); CFG.setProperty("win.h", String.valueOf(b.height));
                }
            }
            saveProfiles();
        } catch (Exception ignored) { }
    }

    static void restoreWindow(JFrame f) {
        f.setSize(1020, 680); f.setLocationRelativeTo(null);
        try {
            Rectangle r = new Rectangle(Integer.parseInt(CFG.getProperty("win.x")), Integer.parseInt(CFG.getProperty("win.y")),
                    Math.max(900, Integer.parseInt(CFG.getProperty("win.w"))), Math.max(560, Integer.parseInt(CFG.getProperty("win.h"))));
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {      // only if still on a connected screen
                Rectangle i = d.getDefaultConfiguration().getBounds().intersection(r);
                if (i.width >= 200 && i.height >= 120) { f.setBounds(r); break; }
            }
        } catch (Exception ignored) { }
        if ("true".equals(CFG.getProperty("win.max"))) f.setExtendedState(JFrame.MAXIMIZED_BOTH);
    }

    // ---------- tray ----------
    void setupTray() {
        if (!SystemTray.isSupported()) return;
        try {
            Image img = javax.imageio.ImageIO.read(PoingSaveManager.class.getResource("/icon.png"));       // tray = the folder icon
            tray = new TrayIcon(img, APP); tray.setImageAutoSize(true);
            PopupMenu m = new PopupMenu(); trayShow = new MenuItem(tr("show_window")); trayExit = new MenuItem(tr("quit"));
            trayShow.addActionListener(e -> showWindow()); trayExit.addActionListener(e -> exitApp()); m.add(trayShow); m.addSeparator(); m.add(trayExit);
            tray.setPopupMenu(m); tray.addActionListener(e -> showWindow()); SystemTray.getSystemTray().add(tray);
        } catch (Exception e) { tray = null; }
    }

    void showWindow() { frame.setVisible(true); frame.setExtendedState(JFrame.NORMAL); frame.toFront(); }

    void exitApp() { saveState(); saveHotkeys(); System.exit(0); }

    void onClose() {
        if (tray != null && anyHotkey()) { saveState(); frame.setVisible(false); toast(tr("running_in_the_background_tray_icon_hotkeys"), false); }
        else exitApp();
    }

    // ===================== start =====================
    /** The one app icon in several sizes (crisp in the title bar, taskbar and Alt+Tab). */
    static List<Image> appIcons() throws IOException {
        java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(PoingSaveManager.class.getResource("/icon.png"));
        List<Image> l = new ArrayList<>();
        for (int sz : new int[]{256, 64, 48, 32, 24, 16}) {
            java.awt.image.BufferedImage b = new java.awt.image.BufferedImage(sz, sz, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = b.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, sz, sz, null); g.dispose(); l.add(b);
        }
        return l;
    }

    public static void main(String[] args) {
        boolean startInTray = Arrays.asList(args).contains("--tray");           // used by "Start with Windows"
        SwingUtilities.invokeLater(() -> {
            loadCfg(); setupLaf();
            JFrame f = new JFrame(APP);
            try { f.setIconImages(appIcons()); } catch (Exception ignored) { }          // window corner + taskbar = the same folder icon as exe and tray
            PoingSaveManager app = new PoingSaveManager();
            f.setContentPane(app.root); f.setMinimumSize(new Dimension(900, 560)); restoreWindow(f);
            app.frame = f; f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { app.onClose(); } });
            f.addComponentListener(new ComponentAdapter() {
                public void componentMoved(ComponentEvent e) { app.stateDirty(); }
                public void componentResized(ComponentEvent e) { app.stateDirty(); } });
            f.addWindowStateListener(e -> app.stateDirty());
            Runtime.getRuntime().addShutdownHook(new Thread(app::saveState, "psm-save"));      // also saves on logoff / kill via exit
            app.setupTray(); app.startBridge(); app.startWatcher();
            boolean hidden = startInTray && app.tray != null && !NICK.isEmpty() && !app.profiles.isEmpty();
            if (!hidden) f.setVisible(true);
            if (NICK.isEmpty()) app.askNick(true);
            if (app.profiles.isEmpty()) app.guard(() -> app.editProfile(null));
        });
    }
}

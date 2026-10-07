package dev.chatmod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Detector {
    public record Verdict(String rule, String detail) {}

    private record Entry(long time, String norm) {}

    // разделители внутри слова (б.л.я.т.ь, б_л_я) и символы-цензура (бл*ть, бл#ть)
    private static final String SEP = "[.\\-_*#@%$,'`~^+=|/]{0,2}";
    private static final String CENSOR = "[*#@%$?!]";

    private static final String FAM = "(?:мам\\p{L}*|мат(?:ь|ер\\p{L}*|и)|отец|отц\\p{L}*|бат[яеюи]|бать\\p{L}*|сестр\\p{L}*"
            + "|бабк\\p{L}*|бабуш\\p{L}*|дедуш\\p{L}*|родител\\p{L}*|семь[яиеюй]\\p{L}*|жен[аыуе]|дочь|дочк\\p{L}*"
            + "|брат\\p{L}*|сын\\p{L}*)";
    private static final String POSS = "(?:тво(?:я|ю|ей|его|й|е|и|их|им|ему))";

    private static final Pattern FAMILY = Pattern.compile("(?<!\\p{L})" + FAM);
    private static final Pattern POSS_FAM = Pattern.compile(
            "(?<!\\p{L})" + POSS + "[\\s.\\-_*]*" + FAM + "|(?<!\\p{L})" + FAM + "[\\s.\\-_*]*" + POSS + "(?!\\p{L})");
    private static final Pattern RUN = Pattern.compile("(.)\\1{11,}");
    private static final Pattern SPACED = Pattern.compile("(?<![\\p{L}\\d])(?:[\\p{L}\\d][ .\\-_]){3,}[\\p{L}\\d](?![\\p{L}\\d])");

    private static final String LOOK_FROM = "acekmopthxy0346";
    private static final String LOOK_TO = "асекмортнхуозчб";

    private final Config cfg;
    private final List<Pattern> insult = new ArrayList<>();
    private final List<Pattern> exact = new ArrayList<>();
    private final List<Pattern> cheat = new ArrayList<>();
    private final List<String> exceptions = new ArrayList<>();
    private final Map<String, Deque<Entry>> history = new HashMap<>();

    public Detector(Config cfg) {
        this.cfg = cfg;
        for (String r : cfg.insultRoots) insult.add(rootPattern(r));
        for (String r : cfg.cheatRoots) cheat.add(rootPattern(r));
        for (String w : cfg.insultExactWords) {
            exact.add(Pattern.compile("(?<!\\p{L})" + Pattern.quote(normalize(w)) + "(?!\\p{L})"));
        }
        for (String e : cfg.exceptions) exceptions.add(normalize(e));
    }

    /** Приводит текст к единому виду: регистр, подмена латиницы/цифр, склейка "б л я", схлопывание повторов */
    public static String normalize(String in) {
        String s = in.toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = LOOK_FROM.indexOf(c);
            sb.append(idx >= 0 ? LOOK_TO.charAt(idx) : c);
        }
        s = sb.toString();

        Matcher m = SPACED.matcher(s);
        StringBuilder joined = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(joined, Matcher.quoteReplacement(m.group().replaceAll("[ .\\-_]", "")));
        }
        m.appendTail(joined);
        s = joined.toString();

        return s.replaceAll("(\\p{L})\\1+", "$1");
    }

    private static Pattern rootPattern(String root) {
        String r = normalize(root);
        StringBuilder sb = new StringBuilder("(?<!\\p{L})");
        for (int i = 0; i < r.length(); i++) {
            String q = Pattern.quote(String.valueOf(r.charAt(i)));
            if (i > 0) sb.append(SEP);
            if (i == 0) sb.append(q);
            else sb.append("(?:").append(q).append("|").append(CENSOR).append(")");
        }
        return Pattern.compile(sb.toString());
    }

    private String findInsult(String t) {
        for (Pattern p : insult) {
            Matcher m = p.matcher(t);
            if (m.find()) return m.group();
        }
        for (Pattern p : exact) {
            Matcher m = p.matcher(t);
            if (m.find()) return m.group();
        }
        return null;
    }

    public synchronized void clear(String nick) {
        history.remove(nick.toLowerCase(Locale.ROOT));
    }

    /** track=false — не запоминать сообщение (для команды /chatmod test) */
    public synchronized Verdict check(String nick, String msg, boolean track) {
        String norm = normalize(msg);
        String t = norm;
        for (String ex : exceptions) t = t.replace(ex, " ");

        // 4.12 читы
        for (Pattern p : cheat) {
            Matcher m = p.matcher(t);
            if (m.find()) return new Verdict("4.12", "читы: «" + m.group() + "»");
        }

        // 4.8 семья
        Matcher pf = POSS_FAM.matcher(t);
        if (pf.find()) return new Verdict("4.8", "семья: «" + pf.group().trim() + "»");
        String ins = findInsult(t);
        if (ins != null && FAMILY.matcher(t).find()) {
            return new Verdict("4.8", "оскорбление + семья: «" + ins + "»");
        }

        // 4.2 оскорбления
        if (ins != null) return new Verdict("4.2", "оскорбление: «" + ins + "»");

        // 4.1 капс
        int letters = 0, upper = 0;
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) upper++;
            }
        }
        if (letters >= cfg.capsMinLetters && (double) upper / letters >= cfg.capsRatio) {
            return new Verdict("4.1", "капс (" + upper + "/" + letters + " заглавных)");
        }

        // 4.1 длинные повторы символов
        if (RUN.matcher(msg).find()) return new Verdict("4.1", "спам символами");

        // 4.1 флуд / повтор
        long now = System.currentTimeMillis();
        Deque<Entry> dq = history.computeIfAbsent(nick.toLowerCase(Locale.ROOT), k -> new ArrayDeque<>());
        long keep = Math.max(cfg.floodSeconds, cfg.repeatSeconds) * 1000L;
        while (!dq.isEmpty() && now - dq.peekFirst().time() > keep) dq.pollFirst();

        int recent = 1, same = 1;
        for (Entry e : dq) {
            if (now - e.time() <= cfg.floodSeconds * 1000L) recent++;
            if (now - e.time() <= cfg.repeatSeconds * 1000L && e.norm().equals(norm)) same++;
        }
        if (track) dq.addLast(new Entry(now, norm));

        if (recent >= cfg.floodMessages) {
            return new Verdict("4.1", "флуд (" + recent + " сообщ. за " + cfg.floodSeconds + " сек)");
        }
        if (same >= cfg.repeatMessages) {
            return new Verdict("4.1", "спам одинаковыми сообщениями (" + same + ")");
        }
        return null;
    }
}

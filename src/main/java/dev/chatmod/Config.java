package dev.chatmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static class RuleCfg {
        public String time = "15m";
        public String reason = "";
        public boolean ip = false;

        public RuleCfg() {}

        public RuleCfg(String time, String reason, boolean ip) {
            this.time = time;
            this.reason = reason;
            this.ip = ip;
        }
    }

    // ---- основное ----
    public String webhookUrl = "PASTE_WEBHOOK_URL_HERE";
    public boolean enabled = true;
    /** true = ничего не мутит, только шлёт отчёт в Discord с пометкой ТЕСТ */
    public boolean dryRun = false;
    public boolean debug = false;

    /** Как распознать ник и сообщение в строке чата. Нужны группы nick и msg. */
    public String chatRegex = "^.*?(?<nick>[A-Za-z0-9_]{3,16})\\s*(?:»|>>|›|:|>)\\s*(?<msg>.+)$";
    /** Наказывать только тех, кто есть в табе (защита от ложных "ников" в системных сообщениях) */
    public boolean requireOnline = true;

    public String muteCommand = "mute {nick} {time} {reason}";
    public String muteIpCommand = "muteip {nick} {time} {reason}";

    public List<String> ignoredPlayers = new ArrayList<>(List.of("ТвойНик", "ДругойМодератор"));

    // ---- тайминги ----
    public int commandDelayTicks = 40;        // пауза между командами (20 тиков = 1 сек)
    public int punishCooldownSeconds = 120;   // не наказывать одного и того же ника чаще

    // ---- 4.1 капс / спам / флуд ----
    public int capsMinLetters = 10;
    public double capsRatio = 0.75;
    public int floodMessages = 5;      // столько сообщений
    public int floodSeconds = 6;       // за столько секунд
    public int repeatMessages = 3;     // одинаковых сообщений
    public int repeatSeconds = 20;     // за столько секунд

    public Map<String, RuleCfg> rules = new LinkedHashMap<>();

    {
        rules.put("4.1", new RuleCfg("15m", "Капс/Спам/Флуд (п. 4.1)", false));
        rules.put("4.2", new RuleCfg("30m", "Унижения/оскорбления/угрозы (п. 4.2)", false));
        rules.put("4.8", new RuleCfg("1h", "Оскорбление/упоминание семьи (п. 4.8)", false));
        rules.put("4.12", new RuleCfg("2d", "Упоминание читов (п. 4.12)", true));
    }

    // ---- словари (корни слов; цензура *, #, @ и т.д. и подмена букв обрабатываются автоматически) ----
    public List<String> insultRoots = new ArrayList<>(List.of(
            "бля", "хуй", "хуе", "хуя", "хуи", "нахуй", "нахуя", "нахер", "похуй", "нихуя", "охуе", "охуи", "захуя",
            "пизд", "распизд", "спизд", "запизд", "опизд", "напизд", "выпизд", "отпизд", "пропизд",
            "пидор", "пидар", "пидр", "педик",
            "еба", "ебл", "ебу", "ебис", "ебен", "заеб", "уеб", "наеб", "выеб", "доеб", "поеб", "разъеб",
            "сука", "сучк", "сучар", "мудак", "мудил", "мудозвон", "гандон", "гондон", "залуп",
            "шлюх", "шалав", "проститутк", "дроч", "говн", "дерьм", "жоп", "хер",
            "мраз", "ублюд", "выродок", "гнид", "падла", "падлюк", "твар", "скотин",
            "дебил", "идиот", "кретин", "имбецил", "олигофрен", "дегенерат", "придурок", "придурк",
            "тупиц", "тупорыл", "дурак", "дура", "нищеброд", "лузер", "лошар", "чмош", "ничтожеств",
            "уродин",
            "сдохни", "сдохн", "убью", "убьем", "зарежу", "прибью", "найду тебя", "взорву", "сватну", "доксну",
            "blyat", "blya", "suka", "pidor", "pidar", "huy", "xuy", "pizd", "ebat", "ebal", "mudak", "gandon"
    ));

    /** Слова, которые считаются только целиком (без звёздочек), чтобы не ловить лишнее */
    public List<String> insultExactWords = new ArrayList<>(List.of(
            "чмо", "лох", "лохи", "лохов", "даун", "дауны", "дауна", "тупой", "тупая", "тупые",
            "урод", "уроды", "уродов"
    ));

    /** 4.12 — читы */
    public List<String> cheatRoots = new ArrayList<>(List.of(
            "нуран", "нурсултан", "nuran", "nursultan", "wexside", "векссайд", "akrien", "акриен"
    ));

    /** Фрагменты, которые вырезаются из текста перед проверкой (против ложных срабатываний) */
    public List<String> exceptions = new ArrayList<>(List.of(
            "херсон", "херувим", "сукно", "суконн", "лохмат", "лохнесс", "лохматый"
    ));

    // ---- загрузка / сохранение ----
    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("chatmoderator.json");
    }

    public static Config load() {
        Path p = path();
        try {
            if (Files.exists(p)) {
                Config c = GSON.fromJson(Files.readString(p), Config.class);
                if (c != null) return c;
            }
        } catch (Exception e) {
            ChatModClient.LOG.error("Не удалось прочитать конфиг, использую значения по умолчанию", e);
        }
        Config c = new Config();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (Exception e) {
            ChatModClient.LOG.error("Не удалось сохранить конфиг", e);
        }
    }
}

package es.normsafio.duelos;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DuelosPlugin extends JavaPlugin implements Listener, TabExecutor {

    static final String PFX = "\u00a76[Duelos] \u00a7f";

    private Plugin casino;
    private Object core;
    private Method coinM;
    private Method itemsM;
    private Method isM;

    record Challenge(UUID from, UUID to, Game game, int bet, long expires) {}

    /** clave = jugador retado */
    final Map<UUID, Challenge> pending = new HashMap<>();
    /** jugador -> duelo en curso */
    final Map<UUID, Duel> duels = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Plugin n = Bukkit.getPluginManager().getPlugin("Normsafio");
        casino = Bukkit.getPluginManager().getPlugin("NormsafioCasino");
        if (n == null || casino == null) {
            getLogger().severe("Faltan Normsafio o NormsafioCasino. Desactivando NormsafioCasinoDuelos.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        core = n;
        try {
            coinM = casino.getClass().getDeclaredMethod("coin");
            coinM.setAccessible(true);
        } catch (Throwable t) {
            getLogger().severe("No encuentro coin() en NormsafioCasino: " + t);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            itemsM = core.getClass().getMethod("items");
            itemsM.setAccessible(true);
        } catch (Throwable t) {
            itemsM = null; // se usara comparacion por isSimilar
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("duelo").setExecutor(this);
        getCommand("duelo").setTabCompleter(this);
        getLogger().info("NormsafioCasinoDuelos activado: Blackjack, Dados y PPT 1 contra 1.");
    }

    @Override
    public void onDisable() {
        // devolver las monedas de los duelos en curso
        for (Duel d : new ArrayList<>(new java.util.HashSet<>(duels.values()))) d.abort();
        duels.clear();
        pending.clear();
    }

    // ------------------------------------------------------------ monedas

    ItemStack coinTemplate() {
        try {
            return (ItemStack) coinM.invoke(casino);
        } catch (Throwable t) {
            getLogger().warning("coin() fallo: " + t);
            return null;
        }
    }

    boolean isCoin(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return false;
        try {
            if (itemsM != null) {
                Object reg = itemsM.invoke(core);
                if (isM == null) {
                    isM = reg.getClass().getMethod("is", ItemStack.class, String.class);
                    isM.setAccessible(true);
                }
                return (boolean) isM.invoke(reg, it, "casino_coin");
            }
        } catch (Throwable ignored) {
            // cae al metodo alternativo
        }
        ItemStack c = coinTemplate();
        return c != null && c.isSimilar(it);
    }

    int countCoins(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (isCoin(it)) n += it.getAmount();
        return n;
    }

    void takeCoins(Player p, int amount) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length && amount > 0; i++) {
            ItemStack it = c[i];
            if (!isCoin(it)) continue;
            int t = Math.min(amount, it.getAmount());
            amount -= t;
            if (t >= it.getAmount()) {
                inv.setItem(i, null);
            } else {
                ItemStack cl = it.clone();
                cl.setAmount(it.getAmount() - t);
                inv.setItem(i, cl);
            }
        }
    }

    void giveCoins(Player p, int amount) {
        if (amount <= 0) return;
        ItemStack coin = coinTemplate();
        if (coin == null) return;
        coin.setAmount(amount);
        Map<Integer, ItemStack> left = p.getInventory().addItem(coin);
        for (ItemStack r : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), r);
    }

    void snd(Player p, org.bukkit.Sound snd) {
        p.playSound(p.getLocation(), snd, 1f, 1f);
    }

    void msg(Player p, String s) {
        p.sendMessage(PFX + s);
    }

    // ------------------------------------------------------------ comandos

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Solo jugadores.");
            return true;
        }
        if (a.length == 0) {
            help(p);
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "aceptar" -> accept(p);
            case "rechazar" -> reject(p);
            case "cancelar" -> cancel(p);
            case "reabrir" -> {
                Duel d = duels.get(p.getUniqueId());
                if (d == null) msg(p, "\u00a7cNo estas en ningun duelo.");
                else d.reopen(p);
            }
            case "juegos" -> {
                msg(p, "Juegos disponibles: \u00a7eblackjack\u00a7f, \u00a7edados\u00a7f, \u00a7eppt\u00a7f.");
            }
            default -> challenge(p, a);
        }
        return true;
    }

    private void help(Player p) {
        msg(p, "\u00a7e/duelo <jugador> <blackjack|dados|ppt> [1|2] \u00a77- retar a alguien");
        msg(p, "\u00a7e/duelo aceptar \u00a77| \u00a7e/duelo rechazar \u00a77| \u00a7e/duelo cancelar \u00a77| \u00a7e/duelo reabrir");
    }

    private boolean enabled(Game g) {
        return getConfig().getBoolean("games." + g.key(), true);
    }

    private void challenge(Player p, String[] a) {
        if (a.length < 2) {
            help(p);
            return;
        }
        if (duels.containsKey(p.getUniqueId())) {
            msg(p, "\u00a7cYa estas en un duelo.");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || !t.isOnline()) {
            msg(p, "\u00a7cEse jugador no esta conectado.");
            return;
        }
        if (t.equals(p)) {
            msg(p, "\u00a7cNo puedes retarte a ti mismo.");
            return;
        }
        if (duels.containsKey(t.getUniqueId())) {
            msg(p, "\u00a7cEse jugador ya esta en un duelo.");
            return;
        }
        Game g = Game.parse(a[1]);
        if (g == null || !enabled(g)) {
            msg(p, "\u00a7cJuego no valido. Usa: blackjack, dados o ppt.");
            return;
        }
        int max = Math.max(1, getConfig().getInt("max-bet", 2));
        int bet = 1;
        if (a.length > 2) {
            try {
                bet = Integer.parseInt(a[2]);
            } catch (NumberFormatException e) {
                bet = -1;
            }
        }
        if (bet < 1 || bet > max) {
            msg(p, "\u00a7cLa apuesta debe ser entre 1 y " + max + " moneda(s).");
            return;
        }
        if (countCoins(p) < bet) {
            msg(p, "\u00a7cNo tienes " + bet + " Moneda(s) Dopamina.");
            return;
        }
        Challenge old = pending.get(t.getUniqueId());
        if (old != null && old.expires() > System.currentTimeMillis() && !old.from().equals(p.getUniqueId())) {
            msg(p, "\u00a7cEse jugador ya tiene un reto pendiente. Espera un momento.");
            return;
        }
        long secs = getConfig().getInt("challenge-expire", 30);
        pending.put(t.getUniqueId(), new Challenge(p.getUniqueId(), t.getUniqueId(), g, bet,
                System.currentTimeMillis() + secs * 1000L));
        msg(p, "Reto enviado a \u00a7e" + t.getName() + "\u00a7f: \u00a7b" + g.nombre + "\u00a7f por \u00a76" + bet
                + " moneda(s) \u00a7fcada uno.");
        t.sendMessage(PFX + "\u00a7e" + p.getName() + " \u00a7fte reta a \u00a7b" + g.nombre + "\u00a7f apostando \u00a76"
                + bet + " moneda(s) \u00a7fcada uno.");
        t.sendMessage(Component.text("   [ACEPTAR]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/duelo aceptar"))
                .append(Component.text("   "))
                .append(Component.text("[RECHAZAR]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/duelo rechazar"))));
    }

    private void accept(Player p) {
        Challenge c = pending.remove(p.getUniqueId());
        if (c == null || c.expires() < System.currentTimeMillis()) {
            msg(p, "\u00a7cNo tienes ningun reto pendiente.");
            return;
        }
        Player from = Bukkit.getPlayer(c.from());
        if (from == null || !from.isOnline()) {
            msg(p, "\u00a7cEl retador se ha desconectado.");
            return;
        }
        if (duels.containsKey(p.getUniqueId()) || duels.containsKey(from.getUniqueId())) {
            msg(p, "\u00a7cAlguno de los dos ya esta en un duelo.");
            return;
        }
        if (countCoins(from) < c.bet()) {
            msg(p, "\u00a7c" + from.getName() + " ya no tiene suficientes monedas.");
            msg(from, "\u00a7cNo tienes monedas suficientes para el duelo.");
            return;
        }
        if (countCoins(p) < c.bet()) {
            msg(p, "\u00a7cNo tienes " + c.bet() + " Moneda(s) Dopamina.");
            return;
        }
        takeCoins(from, c.bet());
        takeCoins(p, c.bet());
        Duel d = new Duel(this, c.game(), c.bet(), from, p);
        duels.put(from.getUniqueId(), d);
        duels.put(p.getUniqueId(), d);
        d.start();
    }

    private void reject(Player p) {
        Challenge c = pending.remove(p.getUniqueId());
        if (c == null) {
            msg(p, "\u00a7cNo tienes ningun reto pendiente.");
            return;
        }
        msg(p, "Reto rechazado.");
        Player from = Bukkit.getPlayer(c.from());
        if (from != null) msg(from, "\u00a7e" + p.getName() + " \u00a7frechazo tu reto.");
    }

    private void cancel(Player p) {
        boolean any = pending.values().removeIf(c -> c.from().equals(p.getUniqueId()));
        msg(p, any ? "Reto cancelado." : "\u00a7cNo tienes retos enviados.");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : new String[]{"aceptar", "rechazar", "cancelar", "reabrir", "juegos"})
                if (x.startsWith(a[0].toLowerCase())) out.add(x);
            for (Player o : Bukkit.getOnlinePlayers())
                if (o.getName().toLowerCase().startsWith(a[0].toLowerCase())) out.add(o.getName());
        } else if (a.length == 2) {
            for (Game g : Game.values()) if (g.key().startsWith(a[1].toLowerCase())) out.add(g.key());
        } else if (a.length == 3) {
            out.add("1");
            out.add("2");
        }
        return out;
    }

    // ------------------------------------------------------------ eventos

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Duel.Side s) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p && p.equals(s.p)
                    && e.getClickedInventory() != null
                    && e.getClickedInventory().equals(e.getView().getTopInventory())) {
                s.duel().click(s, e.getRawSlot());
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Duel.Side) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Duel.Side s && !s.duel().done) {
            Player p = s.p;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!s.duel().done && p.isOnline()) {
                    msg(p, "Cerraste la mesa. Escribe \u00a7e/duelo reabrir \u00a7fpara volver (si no juegas, se jugara solo al acabar el tiempo).");
                }
            }, 1L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        pending.remove(p.getUniqueId());
        pending.values().removeIf(c -> c.from().equals(p.getUniqueId()));
        Duel d = duels.get(p.getUniqueId());
        if (d != null) d.forfeit(p);
    }
}

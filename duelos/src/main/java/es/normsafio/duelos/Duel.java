package es.normsafio.duelos;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Un duelo 1 contra 1 entre dos jugadores reales. Cada uno ve su propia mesa (inventario de 27 huecos). */
final class Duel {

    private static final String[] RANKS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    private static final String[] SUITS = {"\u2660", "\u2665", "\u2666", "\u2663"};
    private static final Random RND = new Random();

    final DuelosPlugin pl;
    final Game game;
    final int bet;
    final Side a;
    final Side b;
    boolean done;
    private boolean reveal;
    private BukkitTask timeout;

    // blackjack
    private final List<Integer> deck = new ArrayList<>();
    private int next;

    /** Lado de un jugador: guarda su estado y es el "holder" de su inventario. */
    final class Side implements InventoryHolder {
        final Player p;
        Inventory inv;
        final List<Integer> hand = new ArrayList<>();
        boolean stand;
        boolean rolled;
        int d1, d2;
        int pick = -1;

        Side(Player p) {
            this.p = p;
        }

        Duel duel() {
            return Duel.this;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    Duel(DuelosPlugin pl, Game game, int bet, Player p1, Player p2) {
        this.pl = pl;
        this.game = game;
        this.bet = bet;
        this.a = new Side(p1);
        this.b = new Side(p2);
    }

    private Side other(Side s) {
        return s == a ? b : a;
    }

    private Side sideOf(Player p) {
        return a.p.equals(p) ? a : b.p.equals(p) ? b : null;
    }

    // ------------------------------------------------------------ inicio

    void start() {
        for (Side s : new Side[]{a, b}) {
            s.inv = Bukkit.createInventory(s, 27, Component.text("Duelo: " + game.nombre));
        }
        if (game == Game.BLACKJACK) {
            for (int i = 0; i < 52; i++) deck.add(i);
            Collections.shuffle(deck);
            for (Side s : new Side[]{a, b}) {
                s.hand.add(deck.get(next++));
                s.hand.add(deck.get(next++));
                if (total(s.hand) == 21) s.stand = true;
            }
        }
        pl.msg(a.p, "Duelo de \u00a7b" + game.nombre + "\u00a7f contra \u00a7e" + b.p.getName() + "\u00a7f. Bote: \u00a76" + (bet * 2) + " moneda(s)\u00a7f.");
        pl.msg(b.p, "Duelo de \u00a7b" + game.nombre + "\u00a7f contra \u00a7e" + a.p.getName() + "\u00a7f. Bote: \u00a76" + (bet * 2) + " moneda(s)\u00a7f.");
        renderAll();
        a.p.openInventory(a.inv);
        b.p.openInventory(b.inv);
        armTimeout();
        if (game == Game.BLACKJACK) check();
    }

    void reopen(Player p) {
        Side s = sideOf(p);
        if (s != null && !done) {
            render(s);
            p.openInventory(s.inv);
        }
    }

    private void armTimeout() {
        if (timeout != null) timeout.cancel();
        long ticks = Math.max(10, pl.getConfig().getInt("turn-timeout", 60)) * 20L;
        timeout = Bukkit.getScheduler().runTaskLater(pl, this::onTimeout, ticks);
    }

    private void onTimeout() {
        if (done || reveal) return;
        for (Side s : new Side[]{a, b}) {
            switch (game) {
                case BLACKJACK -> s.stand = true;
                case DADOS -> {
                    if (!s.rolled) rollDice(s);
                }
                case PPT -> {
                    if (s.pick < 0) s.pick = RND.nextInt(3);
                }
            }
        }
        check();
        if (!done && !reveal) armTimeout();
    }

    // ------------------------------------------------------------ clicks

    void click(Side s, int slot) {
        if (done || reveal) return;
        pl.snd(s.p, Sound.UI_BUTTON_CLICK);
        switch (game) {
            case BLACKJACK -> {
                if (s.stand) return;
                if (slot == 20) {
                    s.hand.add(deck.get(next++));
                    if (total(s.hand) >= 21) s.stand = true;
                } else if (slot == 24) {
                    s.stand = true;
                } else {
                    return;
                }
            }
            case DADOS -> {
                if (slot != 13 || s.rolled) return;
                rollDice(s);
            }
            case PPT -> {
                if (s.pick >= 0) return;
                if (slot == 11) s.pick = 0;
                else if (slot == 13) s.pick = 1;
                else if (slot == 15) s.pick = 2;
                else return;
            }
        }
        renderAll();
        check();
    }

    private void rollDice(Side s) {
        s.d1 = 1 + RND.nextInt(6);
        s.d2 = 1 + RND.nextInt(6);
        s.rolled = true;
    }

    // ------------------------------------------------------------ resolucion

    private void check() {
        if (done) return;
        switch (game) {
            case BLACKJACK -> {
                if (a.stand && b.stand) {
                    int ta = total(a.hand), tb = total(b.hand);
                    boolean ba = ta > 21, bb = tb > 21;
                    Side w = null;
                    if (ba && bb) w = null;
                    else if (ba) w = b;
                    else if (bb) w = a;
                    else if (ta > tb) w = a;
                    else if (tb > ta) w = b;
                    finish(w, "Blackjack: " + a.p.getName() + " " + ta + " - " + b.p.getName() + " " + tb);
                } else {
                    renderAll();
                }
            }
            case DADOS -> {
                if (a.rolled && b.rolled) {
                    int ta = a.d1 + a.d2, tb = b.d1 + b.d2;
                    if (ta == tb) tie();
                    else finish(ta > tb ? a : b, "Dados: " + a.p.getName() + " " + ta + " - " + b.p.getName() + " " + tb);
                }
            }
            case PPT -> {
                if (a.pick >= 0 && b.pick >= 0) {
                    int r = (a.pick - b.pick + 3) % 3;
                    if (r == 0) tie();
                    else finish(r == 1 ? a : b, "PPT: " + a.p.getName() + " " + pickName(a.pick) + " - " + b.p.getName() + " " + pickName(b.pick));
                }
            }
        }
    }

    /** Empate en dados / ppt: se ensenan los resultados y se repite la ronda. */
    private void tie() {
        reveal = true;
        if (timeout != null) timeout.cancel();
        renderAll();
        for (Side s : new Side[]{a, b}) pl.msg(s.p, "\u00a7e\u00a1Empate! Se repite la ronda.");
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (done) return;
            for (Side s : new Side[]{a, b}) {
                s.rolled = false;
                s.pick = -1;
            }
            reveal = false;
            renderAll();
            armTimeout();
        }, 50L);
    }

    private void finish(Side winner, String resumen) {
        if (done) return;
        done = true;
        reveal = true;
        if (timeout != null) timeout.cancel();
        pl.duels.remove(a.p.getUniqueId());
        pl.duels.remove(b.p.getUniqueId());
        renderAll();
        if (winner == null) {
            for (Side s : new Side[]{a, b}) {
                pl.giveCoins(s.p, bet);
                pl.msg(s.p, "\u00a7eEmpate. \u00a7fSe te devuelven tus " + bet + " moneda(s). \u00a77(" + resumen + ")");
            }
        } else {
            Side loser = other(winner);
            pl.giveCoins(winner.p, bet * 2);
            pl.snd(winner.p, Sound.ENTITY_PLAYER_LEVELUP);
            pl.snd(loser.p, Sound.ENTITY_VILLAGER_NO);
            pl.msg(winner.p, "\u00a7a\u00a1Ganaste! \u00a7fTe llevas \u00a76" + (bet * 2) + " moneda(s)\u00a7f. \u00a77(" + resumen + ")");
            pl.msg(loser.p, "\u00a7cPerdiste \u00a7f" + bet + " moneda(s) contra " + winner.p.getName() + ". \u00a77(" + resumen + ")");
            if (pl.getConfig().getBoolean("announce-wins", false)) {
                Bukkit.broadcast(Component.text("\u00a76[Duelos] \u00a7e" + winner.p.getName() + " \u00a7fha ganado \u00a76" + (bet * 2)
                        + " moneda(s) \u00a7fa " + loser.p.getName() + " en " + game.nombre + "."));
            }
        }
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            for (Side s : new Side[]{a, b}) {
                if (s.p.isOnline() && s.p.getOpenInventory().getTopInventory().getHolder() == s) s.p.closeInventory();
            }
        }, 80L);
    }

    /** Un jugador se desconecta: gana el rival. */
    void forfeit(Player quitter) {
        if (done) return;
        Side q = sideOf(quitter);
        if (q == null) return;
        finish(other(q), "abandono de " + quitter.getName());
    }

    /** El plugin se apaga: devolver monedas a todos. */
    void abort() {
        if (done) return;
        done = true;
        if (timeout != null) timeout.cancel();
        for (Side s : new Side[]{a, b}) {
            if (s.p.isOnline()) {
                pl.giveCoins(s.p, bet);
                pl.msg(s.p, "\u00a7eDuelo cancelado. \u00a7fSe te devuelven tus monedas.");
                if (s.p.getOpenInventory().getTopInventory().getHolder() == s) s.p.closeInventory();
            }
        }
    }

    // ------------------------------------------------------------ blackjack

    static int total(List<Integer> hand) {
        int t = 0, aces = 0;
        for (int c : hand) {
            int r = c % 13;
            if (r == 0) {
                aces++;
                t += 11;
            } else {
                t += Math.min(r + 1, 10);
            }
        }
        while (t > 21 && aces > 0) {
            t -= 10;
            aces--;
        }
        return t;
    }

    private static String cardName(int c) {
        int suit = c / 13;
        String color = (suit == 1 || suit == 2) ? "\u00a7c" : "\u00a70";
        return color + RANKS[c % 13] + SUITS[suit];
    }

    private static String pickName(int p) {
        return p == 0 ? "Piedra" : p == 1 ? "Papel" : "Tijera";
    }

    // ------------------------------------------------------------ pintado

    private void renderAll() {
        render(a);
        render(b);
    }

    private ItemStack item(Material m, String name, int amount, String... lore) {
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            List<String> l = new ArrayList<>();
            for (String x : lore) l.add(x);
            meta.setLore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private void render(Side s) {
        Side o = other(s);
        Inventory inv = s.inv;
        ItemStack bg = item(Material.GRAY_STAINED_GLASS_PANE, " ", 1);
        for (int i = 0; i < 27; i++) inv.setItem(i, bg);
        boolean show = reveal || done;
        String head = "\u00a77Bote: \u00a76" + (bet * 2) + " \u00a77| Rival: \u00a7e" + o.p.getName();
        switch (game) {
            case BLACKJACK -> {
                // fila 1: cartas del rival (ocultas hasta el final)
                for (int i = 0; i < Math.min(9, o.hand.size()); i++) {
                    inv.setItem(i, show
                            ? item(Material.PAPER, cardName(o.hand.get(i)), 1)
                            : item(Material.BLACK_STAINED_GLASS_PANE, "\u00a78Carta oculta", 1));
                }
                // fila 2: tus cartas
                for (int i = 0; i < Math.min(9, s.hand.size()); i++) {
                    inv.setItem(9 + i, item(Material.PAPER, cardName(s.hand.get(i)), 1));
                }
                int t = total(s.hand);
                String estado = t > 21 ? "\u00a7cTe pasaste" : s.stand ? "\u00a7aPlantado" : "\u00a7eTu turno";
                inv.setItem(22, item(Material.BOOK, "\u00a7fTu total: \u00a7e" + t, 1, estado, head));
                if (show) {
                    inv.setItem(26, item(Material.BOOK, "\u00a7fTotal rival: \u00a7e" + total(o.hand), 1));
                } else {
                    inv.setItem(26, item(Material.CLOCK, "\u00a7fRival", 1,
                            "\u00a77Cartas: " + o.hand.size(),
                            o.stand ? "\u00a7aSe ha plantado" : "\u00a7ePensando..."));
                }
                if (!s.stand && !done) {
                    inv.setItem(20, item(Material.LIME_DYE, "\u00a7aPedir carta", 1));
                    inv.setItem(24, item(Material.RED_DYE, "\u00a7cPlantarse", 1));
                }
            }
            case DADOS -> {
                if (s.rolled) {
                    inv.setItem(11, item(Material.WHITE_CONCRETE, "\u00a7fDado 1: \u00a7e" + s.d1, s.d1));
                    inv.setItem(12, item(Material.WHITE_CONCRETE, "\u00a7fDado 2: \u00a7e" + s.d2, s.d2));
                } else {
                    inv.setItem(13, item(Material.EMERALD, "\u00a7aTirar los dados", 1, head));
                }
                if (o.rolled && show) {
                    inv.setItem(14, item(Material.BLACK_CONCRETE, "\u00a7fDado 1 rival: \u00a7e" + o.d1, o.d1));
                    inv.setItem(15, item(Material.BLACK_CONCRETE, "\u00a7fDado 2 rival: \u00a7e" + o.d2, o.d2));
                } else {
                    inv.setItem(22, item(Material.CLOCK, "\u00a7fRival", 1,
                            o.rolled ? "\u00a7aYa ha tirado" : "\u00a7ePensando..."));
                }
                if (s.rolled) inv.setItem(4, item(Material.BOOK, "\u00a7fTu suma: \u00a7e" + (s.d1 + s.d2), 1, head));
            }
            case PPT -> {
                if (s.pick < 0) {
                    inv.setItem(11, item(Material.COBBLESTONE, "\u00a77Piedra", 1));
                    inv.setItem(13, item(Material.PAPER, "\u00a7fPapel", 1));
                    inv.setItem(15, item(Material.SHEARS, "\u00a7bTijera", 1));
                } else {
                    Material m = s.pick == 0 ? Material.COBBLESTONE : s.pick == 1 ? Material.PAPER : Material.SHEARS;
                    inv.setItem(13, item(m, "\u00a7aElegiste: \u00a7f" + pickName(s.pick), 1, head));
                }
                if (o.pick >= 0 && show) {
                    Material m = o.pick == 0 ? Material.COBBLESTONE : o.pick == 1 ? Material.PAPER : Material.SHEARS;
                    inv.setItem(22, item(m, "\u00a7cRival: \u00a7f" + pickName(o.pick), 1));
                } else {
                    inv.setItem(22, item(Material.CLOCK, "\u00a7fRival", 1,
                            o.pick >= 0 ? "\u00a7aYa ha elegido" : "\u00a7ePensando..."));
                }
            }
        }
    }
}

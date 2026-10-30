package es.normsafio.duelos;

public enum Game {
    BLACKJACK("Blackjack", "blackjack", "bj", "21"),
    DADOS("Dados", "dados", "dado"),
    PPT("Piedra, Papel o Tijera", "ppt", "piedra", "rps");

    public final String nombre;
    final String[] alias;

    Game(String nombre, String... alias) {
        this.nombre = nombre;
        this.alias = alias;
    }

    public String key() {
        return alias[0];
    }

    public static Game parse(String s) {
        if (s == null) return null;
        String l = s.toLowerCase();
        for (Game g : values()) {
            for (String a : g.alias) if (a.equals(l)) return g;
        }
        return null;
    }
}

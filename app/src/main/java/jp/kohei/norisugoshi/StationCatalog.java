package jp.kohei.norisugoshi;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

public final class StationCatalog {
    public static final class Entry {
        public final String name, lines;
        public final double latitude, longitude;
        final String searchName, searchLines;
        Entry(String name, double latitude, double longitude, String lines) {
            this.name = name; this.latitude = latitude; this.longitude = longitude; this.lines = lines;
            searchName = normalize(name); searchLines = normalize(lines);
        }
    }
    private static volatile List<Entry> cached;
    private StationCatalog() { }

    public static List<Entry> all(Context context) throws IOException {
        List<Entry> data = cached;
        if (data != null) return data;
        synchronized (StationCatalog.class) {
            if (cached != null) return cached;
            List<Entry> loaded = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("stations.tsv"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split("\t", 4);
                    if (fields.length == 4) loaded.add(new Entry(fields[0],
                            Double.parseDouble(fields[1]), Double.parseDouble(fields[2]), fields[3]));
                }
            }
            cached = Collections.unmodifiableList(loaded);
            return cached;
        }
    }

    public static List<Entry> suggest(Context context, String query, double latitude,
                                      double longitude, int limit) throws IOException {
        if (limit <= 0) return Collections.emptyList();
        String search = normalize(query);
        Comparator<Match> order = Comparator.comparingInt((Match m) -> m.rank)
                .thenComparingDouble(m -> m.distance).thenComparing(m -> m.entry.name);
        PriorityQueue<Match> best = new PriorityQueue<>(limit, order.reversed());
        for (Entry entry : all(context)) {
            int rank = search.isEmpty() || entry.searchName.equals(search) ? 0
                    : entry.searchName.startsWith(search) ? 1
                    : entry.searchName.contains(search) ? 2
                    : entry.searchLines.contains(search) ? 3 : -1;
            if (rank < 0) continue;
            Match match = new Match(entry, rank, Geo.distanceMeters(latitude, longitude,
                    entry.latitude, entry.longitude));
            best.offer(match);
            if (best.size() > limit) best.poll();
        }
        List<Match> sorted = new ArrayList<>(best); sorted.sort(order);
        List<Entry> results = new ArrayList<>();
        for (Match match : sorted) results.add(match.entry);
        return results;
    }

    static String normalize(String value) {
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return text.endsWith("駅") ? text.substring(0, text.length() - 1) : text;
    }
    private static final class Match {
        final Entry entry; final int rank; final double distance;
        Match(Entry entry, int rank, double distance) {
            this.entry = entry; this.rank = rank; this.distance = distance;
        }
    }
}

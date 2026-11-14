package jp.kohei.norisugoshi;

import java.time.*;
import org.robolectric.util.ReflectionHelpers;

final class LastTrainFixtures {
    static long night(){long now=OffsetDateTime.parse("2026-10-13T21:00:00+09:00").toInstant().toEpochMilli();set(now);return now;}
    static void set(long now){ReflectionHelpers.setStaticField(LastTrainEngine.class,"clock",Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));}
    static void reset(){ReflectionHelpers.setStaticField(LastTrainEngine.class,"clock",Clock.systemUTC());}
}

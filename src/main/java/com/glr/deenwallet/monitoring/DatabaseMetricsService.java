package com.glr.deenwallet.monitoring;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Health and size of the PostgreSQL database, read with a few quick read-only queries. */
@Service
public class DatabaseMetricsService {

    @PersistenceContext
    private EntityManager em;

    private final DataSource dataSource;

    public DatabaseMetricsService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static double d(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }
    private static long l(Object o) { return o instanceof Number n ? n.longValue() : 0L; }

    // No single transaction on purpose: if one statistic cannot be read, the others still work.
    @SuppressWarnings("unchecked")
    public Map<String, Object> collect() {
        Map<String, Object> db = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        try {
            em.createNativeQuery("SELECT 1").getSingleResult();
            db.put("up", true);
            db.put("latencyMs", Math.round((System.nanoTime() - t0) / 100_000.0) / 10.0);
        } catch (RuntimeException e) {
            db.put("up", false);
            db.put("error", "The database did not answer: " + e.getClass().getSimpleName());
            return db;
        }
        try {
            String v = String.valueOf(em.createNativeQuery("SELECT version()").getSingleResult());
            String[] parts = v.split(" ");
            db.put("version", parts.length >= 2 ? parts[0] + " " + parts[1] : v);
        } catch (RuntimeException ignored) { }
        try {
            db.put("sizeBytes", l(em.createNativeQuery("SELECT pg_database_size(current_database())").getSingleResult()));
        } catch (RuntimeException ignored) { }
        try {
            db.put("uptimeSeconds", l(em.createNativeQuery("SELECT EXTRACT(EPOCH FROM (now() - pg_postmaster_start_time()))::bigint").getSingleResult()));
        } catch (RuntimeException ignored) { }
        try {
            Map<String, Long> states = new LinkedHashMap<>();
            long all = 0;
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    "SELECT COALESCE(state, 'other'), COUNT(*) FROM pg_stat_activity WHERE datname = current_database() GROUP BY 1").getResultList()) {
                states.put(String.valueOf(r[0]), l(r[1]));
                all += l(r[1]);
            }
            db.put("connections", all);
            db.put("connectionStates", states);
            db.put("maxConnections", l(em.createNativeQuery("SELECT setting::bigint FROM pg_settings WHERE name = 'max_connections'").getSingleResult()));
        } catch (RuntimeException ignored) { }
        try {
            Object[] r = (Object[]) em.createNativeQuery(
                    "SELECT xact_commit, xact_rollback, blks_hit, blks_read, deadlocks, temp_files FROM pg_stat_database WHERE datname = current_database()").getSingleResult();
            double hit = d(r[2]), read = d(r[3]);
            db.put("cacheHitPercent", hit + read > 0 ? Math.round(1000.0 * hit / (hit + read)) / 10.0 : 100.0);
            db.put("commits", l(r[0]));
            db.put("rollbacks", l(r[1]));
            db.put("deadlocks", l(r[4]));
            db.put("tempFiles", l(r[5]));
        } catch (RuntimeException ignored) { }
        try {
            db.put("longestQuerySeconds", Math.round(10.0 * d(em.createNativeQuery(
                    "SELECT COALESCE(MAX(EXTRACT(EPOCH FROM (now() - query_start))), 0) FROM pg_stat_activity "
                            + "WHERE datname = current_database() AND state = 'active' AND pid <> pg_backend_pid()").getSingleResult())) / 10.0);
        } catch (RuntimeException ignored) { }
        try {
            List<Map<String, Object>> tables = new ArrayList<>();
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    "SELECT relname, pg_total_relation_size(relid), n_live_tup FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 6").getResultList()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", r[0]);
                t.put("bytes", l(r[1]));
                t.put("rows", l(r[2]));
                tables.add(t);
            }
            db.put("biggestTables", tables);
        } catch (RuntimeException ignored) { }
        try {
            if (dataSource instanceof HikariDataSource h && h.getHikariPoolMXBean() != null) {
                Map<String, Object> pool = new LinkedHashMap<>();
                pool.put("active", h.getHikariPoolMXBean().getActiveConnections());
                pool.put("idle", h.getHikariPoolMXBean().getIdleConnections());
                pool.put("waiting", h.getHikariPoolMXBean().getThreadsAwaitingConnection());
                pool.put("max", h.getMaximumPoolSize());
                db.put("pool", pool);
            }
        } catch (RuntimeException | LinkageError ignored) { }
        return db;
    }
}

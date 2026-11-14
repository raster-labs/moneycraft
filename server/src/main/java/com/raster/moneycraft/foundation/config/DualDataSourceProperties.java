package com.raster.moneycraft.foundation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "moneycraft.datasource")
public class DualDataSourceProperties {

    private final ClusterPool authority = new ClusterPool();
    private final ClusterPool business = new ClusterPool();

    public ClusterPool getAuthority() {
        return authority;
    }

    public ClusterPool getBusiness() {
        return business;
    }

    public static class ClusterPool {
        private String jdbcUrl;
        private String username;
        private String password;
        private String migratorUsername;
        private String migratorPassword;
        private int maximumPoolSize = 10;
        private int minimumIdle = 2;

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getMigratorUsername() {
            return migratorUsername;
        }

        public void setMigratorUsername(String migratorUsername) {
            this.migratorUsername = migratorUsername;
        }

        public String getMigratorPassword() {
            return migratorPassword;
        }

        public void setMigratorPassword(String migratorPassword) {
            this.migratorPassword = migratorPassword;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public int getMinimumIdle() {
            return minimumIdle;
        }

        public void setMinimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
        }
    }
}

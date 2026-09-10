package talkingheads.repository

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import talkingheads.config.AppConfig
import javax.sql.DataSource

object DatabaseFactory {
    fun create(config: AppConfig): DataSource {
        val hikari = HikariConfig().apply {
            jdbcUrl = requireNotNull(config.databaseUrl)
            config.databaseUser?.let { username = it }
            config.databasePassword?.let { password = it }
            maximumPoolSize = 8
            minimumIdle = 1
            connectionTimeout = 5_000
            validationTimeout = 2_000
            isAutoCommit = true
        }
        return HikariDataSource(hikari).also { dataSource ->
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .table("flyway_schema_history_integrated_backend")
                .load()
                .migrate()
        }
    }
}

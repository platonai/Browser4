package ai.platon.pulsar.rest.config

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CoroutineConfig {

    private val _applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Application-level coroutine scope for background tasks.
     * Uses SupervisorJob so individual child failures don't cancel the whole scope.
     */
    @Bean
    fun applicationScope(): CoroutineScope = _applicationScope

    @PreDestroy
    fun cancelScope() {
        _applicationScope.cancel()
    }
}

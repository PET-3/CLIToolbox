package com.example.clitoolbox.analyzer

import com.example.clitoolbox.core.model.Tool

/**
 * Extension point for SELECT / MULTI_SELECT schema arguments whose valid
 * values depend on the specific tool build rather than being fixed forever
 * (the canonical example: FFmpeg's available encoders/decoders/formats/filters
 * vary by build — a build with libx265 disabled shouldn't offer it).
 *
 * A SchemaArgument can reference a provider by id via [com.example.clitoolbox.core.schema.SchemaArgument.valuesSource].
 * Analyzers resolve providers through [DynamicValueProviderRegistry] while
 * building a Schema (i.e. on the background thread analysis already runs on),
 * with a static fallback list used if no provider is registered or it fails —
 * so a missing/broken provider degrades gracefully instead of breaking Schema
 * generation. This keeps the "don't hardcode tool capabilities into UI" rule
 * intact: only Analyzers ever call a provider; the GUI Generator only ever
 * sees the resulting plain `values: List<String>` on the Schema.
 */
fun interface DynamicValueProvider {
    fun provideValues(tool: Tool): List<String>
}

object DynamicValueProviderRegistry {
    private val providers = mutableMapOf<String, DynamicValueProvider>()

    fun register(id: String, provider: DynamicValueProvider) {
        providers[id] = provider
    }

    fun isRegistered(id: String): Boolean = providers.containsKey(id)

    /** Resolves [id] against [tool], falling back to [fallback] if unregistered, empty, or throwing. */
    fun resolve(id: String, tool: Tool, fallback: List<String>): List<String> {
        val provider = providers[id] ?: return fallback
        return try {
            provider.provideValues(tool).ifEmpty { fallback }
        } catch (e: Exception) {
            fallback
        }
    }
}

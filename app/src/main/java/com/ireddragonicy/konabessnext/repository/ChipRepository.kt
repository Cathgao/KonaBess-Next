package com.ireddragonicy.konabessnext.repository

import com.ireddragonicy.konabessnext.model.ChipDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class ChipRepository @Inject constructor() : ChipRepositoryInterface {
    private val _definitions = MutableStateFlow<List<ChipDefinition>>(emptyList())
    override val definitions: StateFlow<List<ChipDefinition>> = _definitions.asStateFlow()

    private val _currentChip = MutableStateFlow<ChipDefinition?>(null)
    override val currentChip: StateFlow<ChipDefinition?> = _currentChip.asStateFlow()

    override fun setCurrentChip(chip: ChipDefinition?) {
        _currentChip.value = chip
        if (chip != null && !_definitions.value.contains(chip)) {
            _definitions.value = _definitions.value + chip
        }
    }

    override fun getChipById(id: String): ChipDefinition? {
        return _definitions.value.find { it.id == id }
    }

    override fun getLevelsForCurrentChip(): IntArray {
        val c = _currentChip.value ?: return IntArray(0)
        val size = c.levelCount
        return IntArray(size) { it + 1 }
    }

    override fun getLevelStringsForCurrentChip(): Array<String> {
        val c = _currentChip.value ?: return emptyArray()
        val size = c.levelCount
        val resolved = c.resolvedLevels
        return Array(size) { i ->
            resolved[i] ?: (i + 1).toString()
        }
    }
}

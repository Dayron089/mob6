package com.example.rickandmorty.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rickandmorty.data.local.entity.FavoritePokemonEntity
import com.example.rickandmorty.data.local.entity.HistoryEntity
import com.example.rickandmorty.data.model.PokemonDetail
import com.example.rickandmorty.data.model.PokemonResult
import com.example.rickandmorty.data.repository.PokemonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ListUiState {
    data object Loading : ListUiState()
    data class Success(val pokemon: List<PokemonResult>) : ListUiState()
    data object Empty : ListUiState()
    data class Error(val message: String) : ListUiState()
}

sealed class DetailUiState {
    data object Loading : DetailUiState()
    data class Success(val pokemon: PokemonDetail) : DetailUiState()
    data class Error(val message: String) : DetailUiState()
}

enum class ViewMode { ALL, FAVORITES_ONLY }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: PokemonRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.ALL)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _refreshTrigger = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val refreshTrigger: SharedFlow<Unit> = _refreshTrigger.asSharedFlow()

    val favorites: StateFlow<List<FavoritePokemonEntity>> =
        repository.favorites.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val favoriteIds: StateFlow<Set<Int>> =
        repository.favorites
            .map { list -> list.mapTo(mutableSetOf(), FavoritePokemonEntity::id) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptySet()
            )

    val history: StateFlow<List<HistoryEntity>> =
        repository.history.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val rawListResult: Flow<RawListResult> = combine(
        _searchQuery.debounce(300).distinctUntilChanged(),
        _refreshTrigger.onStart { emit(Unit) }
    ) { query, _ -> query }
        .flatMapLatest { query ->
            flow {
                emit(RawListResult.Loading)
                val result = repository.getPokemonList(query.ifBlank { null })
                emit(
                    result.fold(
                        onSuccess = { RawListResult.Loaded(it) },
                        onFailure = { RawListResult.Failed(it.message ?: "Unknown error") }
                    )
                )
            }
        }

    val listUiState: StateFlow<ListUiState> = combine(
        rawListResult,
        _viewMode,
        repository.favorites
    ) { result, mode, favs ->
        applyMode(result, mode, favs.mapTo(mutableSetOf()) { it.id })
    }
        .catch { e -> emit(ListUiState.Error(e.message ?: "Unexpected error")) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ListUiState.Loading
        )

    private fun applyMode(
        result: RawListResult,
        mode: ViewMode,
        favoriteIds: Set<Int>
    ): ListUiState = when (result) {
        RawListResult.Loading -> ListUiState.Loading
        is RawListResult.Failed -> when (mode) {
            ViewMode.FAVORITES_ONLY -> ListUiState.Empty
            ViewMode.ALL -> ListUiState.Error(result.message)
        }
        is RawListResult.Loaded -> {
            val visible = when (mode) {
                ViewMode.ALL -> result.list
                ViewMode.FAVORITES_ONLY -> result.list.filter { it.id in favoriteIds }
            }
            if (visible.isEmpty()) ListUiState.Empty
            else ListUiState.Success(visible)
        }
    }

    private val _detailUiState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val detailUiState: StateFlow<DetailUiState> = _detailUiState.asStateFlow()

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
    }

    fun refresh() {
        _refreshTrigger.tryEmit(Unit)
    }

    private var detailJob: kotlinx.coroutines.Job? = null

    fun loadPokemonDetails(name: String) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _detailUiState.value = DetailUiState.Loading
            val result = repository.getPokemonDetail(name)
            result.onSuccess { detail ->
                _detailUiState.value = DetailUiState.Success(detail)
                repository.recordHistory(detail)
            }.onFailure { e ->
                _detailUiState.value = DetailUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun toggleFavorite(detail: PokemonDetail) {
        viewModelScope.launch {
            repository.toggleFavorite(detail, favoriteIds.value.contains(detail.id))
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    private sealed interface RawListResult {
        data object Loading : RawListResult
        data class Loaded(val list: List<PokemonResult>) : RawListResult
        data class Failed(val message: String) : RawListResult
    }
}

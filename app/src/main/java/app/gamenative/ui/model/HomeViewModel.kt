package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import app.gamenative.ui.data.HomeState
import app.gamenative.ui.enums.HomeDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {
    private val _homeState = MutableStateFlow(HomeState())
    val homeState: StateFlow<HomeState> = _homeState.asStateFlow()

    fun onDestination(destination: HomeDestination) {
        _homeState.update { currentState ->
            currentState.copy(currentDestination = destination)
        }
    }

    fun onConfirmExit(value: Boolean) {
        _homeState.update { currentState ->
            currentState.copy(confirmExit = value)
        }
    }
}

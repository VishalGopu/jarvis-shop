package com.jarvis.shop.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.jarvis.shop.AppGraph
import com.jarvis.shop.BuildConfig
import com.jarvis.shop.ui.onboarding.ServerCheck
import com.jarvis.shop.ui.onboarding.ConnectScreen
import com.jarvis.shop.ui.onboarding.CreateDetailsScreen
import com.jarvis.shop.ui.onboarding.CreateTypeScreen
import com.jarvis.shop.ui.onboarding.JoinOrCreateScreen
import com.jarvis.shop.ui.onboarding.JoinScreen
import com.jarvis.shop.ui.onboarding.OnboardingViewModel
import com.jarvis.shop.ui.onboarding.RolePickScreen
import com.jarvis.shop.ui.onboarding.ShopReadyScreen
import com.jarvis.shop.ui.onboarding.WelcomeScreen
import com.jarvis.shop.ui.onboarding.scanQr
import com.jarvis.shop.ui.shell.ShellScreen
import kotlinx.serialization.Serializable

@Serializable data object Welcome : NavKey
@Serializable data object Connect : NavKey
@Serializable data object Choose : NavKey
@Serializable data object Join : NavKey
@Serializable data object CreateType : NavKey
@Serializable data object CreateDetails : NavKey
@Serializable data object ShopReady : NavKey
@Serializable data object RolePick : NavKey

@Composable
fun RootNav(graph: AppGraph) {
    val session by graph.session.state.collectAsState()
    if (session.signedIn) ShellScreen(graph) else Onboarding(graph)
}

@Composable
private fun Onboarding(graph: AppGraph) {
    val vm: OnboardingViewModel = viewModel(factory = viewModelFactory { initializer { OnboardingViewModel(graph.session, graph.api) } })
    val back = rememberNavBackStack(Welcome)
    val ctx = LocalContext.current
    val pop: () -> Unit = { if (back.size > 1) back.removeAt(back.lastIndex) }
    // After the server answers: multi-shop servers need join/create, single-shop ones only need a role.
    val afterConnect: () -> Unit = { back.add(if (vm.state.value.mode?.multi == true) Choose else RolePick) }
    val scan = {
        scanQr(ctx, onResult = { vm.applyScan(it) { if (back.lastOrNull() != Connect) back.add(Connect); afterConnect() } },
            onError = { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() })
    }
    val st by vm.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(st.createdCode) { if (st.createdCode != null && back.lastOrNull() != ShopReady) back.add(ShopReady) }
    // the embedded server did not answer: fall back to the manual address screen so the app is never stuck
    androidx.compose.runtime.LaunchedEffect(st.check) { if (st.check is ServerCheck.Failed && back.lastOrNull() == Welcome) back.add(Connect) }
    NavDisplay(
        backStack = back,
        onBack = { pop() },
        entryProvider = entryProvider {
            entry<Welcome> { WelcomeScreen(onStart = { if (BuildConfig.DEFAULT_SERVER_URL.isNotBlank()) vm.checkServer { afterConnect() } else back.add(Connect) }, onScan = scan) }
            entry<Connect> { ConnectScreen(vm, onBack = { pop() }, onScan = scan, onConnected = afterConnect) }
            entry<RolePick> { RolePickScreen(vm, onBack = { pop() }) }
            entry<Choose> { JoinOrCreateScreen(onBack = { pop() }, onJoin = { back.add(Join) }, onCreate = { back.add(CreateType) }) }
            entry<Join> { JoinScreen(vm, onBack = { pop() }) }
            entry<CreateType> { CreateTypeScreen(vm, onBack = { pop() }, onNext = { back.add(CreateDetails) }) }
            entry<CreateDetails> { CreateDetailsScreen(vm, onBack = { pop() }) }
            entry<ShopReady> { ShopReadyScreen(vm) }
        },
    )
}

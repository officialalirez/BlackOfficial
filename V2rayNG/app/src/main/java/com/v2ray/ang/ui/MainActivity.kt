package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.databinding.BlacktunOverlayBinding
import com.v2ray.ang.databinding.DialogBlacktunUsernameBinding
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BLACKTUN_SUBSCRIPTION_ID = "blacktun_subscription"
private const val BLACKTUN_FREE_ID = "blacktun_free"
private const val BLACKTUN_PLAN_URL = "https://alirez.n-cpanel.xyz/Sub/Plans/Full.txt"
private const val BLACKTUN_FREE_URL = "https://alirez.n-cpanel.xyz/Sub/Plans/Free.txt"
private const val BLACKTUN_MODE_SUBSCRIPTION = "subscription"
private const val BLACKTUN_MODE_FREE = "free"
private const val PREF_BLACKTUN_MODE = "blacktun_mode"
private const val PREF_BLACKTUN_USERNAME = "blacktun_username"
private const val BLACKTUN_USER_AGENT = "BlackTun/${BuildConfig.VERSION_NAME}"
private const val BLACKTUN_PING_TIMEOUT_MS = 45_000L

private enum class BlackTunMode {
    SUBSCRIPTION,
    FREE
}

private data class BlackTunSource(
    val mode: BlackTunMode,
    val subId: String,
    val otherSubId: String,
    val remarks: String,
    val url: String,
    val username: String? = null
)

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }
    private val blackTunBinding by lazy {
        BlacktunOverlayBinding.bind(binding.blacktunOverlayInclude.root)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var blackTunConnectJob: Job? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        } else {
            showBlackTunMessage(false, getString(R.string.blacktun_vpn_permission_denied))
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.title_server))
        binding.toolbar.visibility = View.GONE
        binding.toolbar.navigationIcon = null
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        setupBlackTunOverlay()

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        // setup navigation drawer
        setupNavigationDrawer()

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }

        setupGroupTab()
        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupNavigationDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupBlackTunOverlay() {
        blackTunBinding.blacktunOverlay.visibility = View.VISIBLE
        blackTunBinding.blacktunConnectButton.setOnClickListener { handleBlackTunConnectClick() }
        blackTunBinding.blacktunSubscriptionButton.setOnClickListener { showBlackTunUsernameDialog() }
        blackTunBinding.blacktunFreeButton.setOnClickListener { loadFreeConfigs() }
        updateBlackTunSourceBadge()
        applyRunningState(false, mainViewModel.isRunning.value == true)
    }

    private fun showBlackTunUsernameDialog() {
        val dialogView = DialogBlacktunUsernameBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, R.style.BlackTunDialogTheme)
            .setView(dialogView.root)
            .setCancelable(true)
            .create()
        dialog.show()
        dialogView.blacktunUsernameInput.postDelayed({
            dialogView.blacktunUsernameInput.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }, 200)
        dialogView.blacktunUsernameDone.setOnClickListener {
            val username = dialogView.blacktunUsernameInput.text.toString().trim()
            if (username.isBlank()) {
                dialogView.blacktunUsernameLayout.error = getString(R.string.blacktun_enter_username)
                return@setOnClickListener
            }
            dialog.dismiss()
            loadSubscriptionConfigs(username)
        }
        dialogView.blacktunUsernameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialogView.blacktunUsernameDone.performClick()
                true
            } else {
                false
            }
        }
    }

    private fun loadSubscriptionConfigs(username: String) {
        val trimmedUsername = username.trim()
        val source = BlackTunSource(
            mode = BlackTunMode.SUBSCRIPTION,
            subId = BLACKTUN_SUBSCRIPTION_ID,
            otherSubId = BLACKTUN_FREE_ID,
            remarks = "BlackTun $trimmedUsername",
            url = "",
            username = trimmedUsername
        )
        loadBlackTunConfigs(source) {
            val planText = fetchBlackTunText(BLACKTUN_PLAN_URL).replace("&amp;", "&")
            val subscriptionUrl = findSubscriptionUrlForUsername(planText, trimmedUsername)
            if (subscriptionUrl.isNullOrBlank()) {
                throw IllegalArgumentException("username_not_found")
            }
            val configText = fetchBlackTunText(subscriptionUrl)
            source.copy(url = subscriptionUrl) to configText
        }
    }

    private fun loadFreeConfigs() {
        val source = BlackTunSource(
            mode = BlackTunMode.FREE,
            subId = BLACKTUN_FREE_ID,
            otherSubId = BLACKTUN_SUBSCRIPTION_ID,
            remarks = "BlackTun Free",
            url = BLACKTUN_FREE_URL
        )
        loadBlackTunConfigs(source) {
            source to fetchBlackTunText(BLACKTUN_FREE_URL)
        }
    }

    private fun loadBlackTunConfigs(
        source: BlackTunSource,
        fetcher: suspend () -> Pair<BlackTunSource, String>
    ) {
        blackTunBinding.blacktunConnectButton.isEnabled = false
        blackTunBinding.blacktunSubscriptionButton.isEnabled = false
        blackTunBinding.blacktunFreeButton.isEnabled = false
        val previousJob = blackTunConnectJob
        blackTunConnectJob = lifecycleScope.launch {
            previousJob?.cancel()
            previousJob?.join()
            mainViewModel.realPingFinishedAction = null
            showBlackTunLoading(getString(R.string.blacktun_loading_source))
            saveBlackTunSource(source)
            updateBlackTunSourceBadge()
            try {
                clearBlackTunSources(source)
                val (finalSource, configText) = fetcher()
                ensureBlackTunSubscription(finalSource)
                val (count, countSub) = importBlackTunConfigText(configText, finalSource.subId)
                if (count + countSub <= 0) {
                    showBlackTunMessage(false, getString(R.string.blacktun_no_config))
                    return@launch
                }
                mainViewModel.subscriptionIdChanged(finalSource.subId)
                mainViewModel.reloadServerList()
                setupGroupTab()
                refreshGroupTabTitles()
                saveBlackTunSource(finalSource)
                updateBlackTunSourceBadge()
                val successMessage = if (finalSource.mode == BlackTunMode.FREE) {
                    getString(R.string.blacktun_loaded_free)
                } else {
                    getString(R.string.blacktun_loaded_subscription)
                }
                showBlackTunMessage(true, successMessage)
            } catch (e: IllegalArgumentException) {
                if (e.message == "username_not_found") {
                    showBlackTunMessage(false, getString(R.string.blacktun_username_not_found))
                } else {
                    showBlackTunMessage(false, e.message ?: getString(R.string.blacktun_fetch_failed))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "BlackTun load failed", e)
                showBlackTunMessage(false, getString(R.string.blacktun_fetch_failed))
            } finally {
                blackTunBinding.blacktunConnectButton.isEnabled = true
                blackTunBinding.blacktunSubscriptionButton.isEnabled = true
                blackTunBinding.blacktunFreeButton.isEnabled = true
                hideBlackTunLoading()
            }
        }
    }

    private fun clearBlackTunSources(source: BlackTunSource) {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        MmkvManager.removeSubscription(source.otherSubId)
        MmkvManager.removeSubscription(source.subId)
    }

    private fun ensureBlackTunSubscription(source: BlackTunSource) {
        val existing = MmkvManager.decodeSubscription(source.subId) ?: SubscriptionItem()
        existing.remarks = source.remarks
        existing.url = source.url
        existing.enabled = true
        existing.autoUpdate = false
        existing.updateInterval = 1440
        existing.allowInsecureUrl = false
        MmkvManager.encodeSubscription(source.subId, existing)
    }

    private suspend fun importBlackTunConfigText(rawText: String, subId: String): Pair<Int, Int> {
        val decodedText = Utils.decode(rawText)
        val sourceText = if (
            decodedText.isNotBlank() &&
            decodedText != rawText &&
            decodedText.lines().any { Utils.isValidSubUrl(it.trim()) } &&
            rawText.lines().none { Utils.isValidSubUrl(it.trim()) }
        ) {
            decodedText
        } else {
            rawText
        }
        val configText = resolveBlackTunSubscriptionUrls(sourceText)
        return AngConfigManager.importBatchConfig(configText, subId, false)
    }

    private suspend fun resolveBlackTunSubscriptionUrls(rawText: String): String {
        var currentText = rawText.replace("&amp;", "&")
        repeat(3) {
            val subscriptionUrls = currentText.lines()
                .map { it.trim() }
                .filter { Utils.isValidSubUrl(it) }
                .distinct()
            if (subscriptionUrls.isEmpty()) {
                return currentText
            }
            val fetched = subscriptionUrls
                .mapNotNull { runCatching { fetchBlackTunText(it) }.getOrNull().orEmpty().ifBlank { null } }
                .joinToString("\n")
            if (fetched.isBlank()) {
                return currentText
            }
            currentText = fetched
        }
        return currentText
    }

    private suspend fun pingAndSortBlackTun(subId: String) {
        mainViewModel.subscriptionIdChanged(subId)
        mainViewModel.reloadServerList()
        if (mainViewModel.serversCache.isEmpty()) {
            mainViewModel.realPingFinishedAction = null
            return
        }
        var finished = false
        mainViewModel.realPingFinishedAction = { status ->
            if (status == "0") {
                finished = true
            }
        }
        try {
            mainViewModel.testAllRealPing()
            val deadline = System.currentTimeMillis() + BLACKTUN_PING_TIMEOUT_MS
            while (!finished && System.currentTimeMillis() < deadline) {
                delay(250)
            }
        } finally {
            mainViewModel.realPingFinishedAction = null
        }
        mainViewModel.sortByTestResults()
        withContext(Dispatchers.Main) {
            mainViewModel.reloadServerList()
            refreshGroupTabTitles()
        }
    }

    private suspend fun fetchBlackTunText(url: String): String = withContext(Dispatchers.IO) {
        val idnUrl = runCatching { HttpUtil.toIdnUrl(url) }.getOrDefault(url)
        val directRequest = UrlContentRequest(
            url = idnUrl,
            timeout = 15000,
            userAgent = BLACKTUN_USER_AGENT
        )
        runCatching { HttpUtil.getUrlContentWithUserAgent(directRequest) }
            .getOrNull()
            ?.ifBlank { null }
            ?: runCatching {
                HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 15000))
            }.getOrNull()
            .orEmpty()
    }

    private fun findSubscriptionUrlForUsername(content: String, username: String): String? {
        if (username.isBlank()) {
            return null
        }
        val wanted = username.trim()
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && Utils.isValidUrl(it) }
            .distinct()
            .firstOrNull { line ->
                val querySub = runCatching { Uri.parse(line).getQueryParameter("sub") }.getOrNull()
                val decodedQuerySub = querySub?.let { Uri.decode(it) }
                decodedQuerySub?.equals(wanted, ignoreCase = true) == true ||
                        Regex("(?:^|[?&])sub=([^&#]+)", RegexOption.IGNORE_CASE)
                            .find(line)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.let { Uri.decode(it).equals(wanted, ignoreCase = true) } == true
            }
    }

    private fun handleBlackTunConnectClick() {
        val source = currentBlackTunSource()
        if (source == null) {
            showBlackTunMessage(false, getString(R.string.blacktun_please_login_first))
            return
        }
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
            return
        }
        if (blackTunConnectJob?.isActive == true) {
            return
        }
        blackTunConnectJob = lifecycleScope.launch {
            blackTunBinding.blacktunConnectButton.isEnabled = false
            showBlackTunLoading(getString(R.string.blacktun_pinging))
            try {
                pingAndSortBlackTun(source.subId)
                val bestGuid = findBestBlackTunServer(source.subId)
                if (bestGuid.isNullOrBlank()) {
                    showBlackTunMessage(false, getString(R.string.blacktun_no_best_config))
                    return@launch
                }
                MmkvManager.setSelectServer(bestGuid)
                mainViewModel.reloadServerList()
                refreshGroupTabTitles()
                showBlackTunMessage(true, getString(R.string.blacktun_selecting_best))
                if (SettingsManager.isVpnMode()) {
                    val intent = VpnService.prepare(this@MainActivity)
                    if (intent == null) {
                        startV2Ray()
                    } else {
                        requestVpnPermission.launch(intent)
                    }
                } else {
                    startV2Ray()
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "BlackTun connect failed", e)
                showBlackTunMessage(false, getString(R.string.blacktun_fetch_failed))
            } finally {
                blackTunBinding.blacktunConnectButton.isEnabled = true
                hideBlackTunLoading()
            }
        }
    }

    private fun findBestBlackTunServer(subId: String): String? {
        val guids = MmkvManager.decodeServerList(subId)
        if (guids.isEmpty()) {
            return null
        }
        return guids
            .map { guid ->
                val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: Long.MAX_VALUE
                val score = when {
                    delay > 0L -> delay
                    delay == 0L -> Long.MAX_VALUE - 1L
                    else -> Long.MAX_VALUE
                }
                guid to score
            }
            .sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first })
            .firstOrNull()
            ?.first
    }

    private fun currentBlackTunSource(): BlackTunSource? {
        val mode = MmkvManager.decodeSettingsString(PREF_BLACKTUN_MODE, "")
        val username = MmkvManager.decodeSettingsString(PREF_BLACKTUN_USERNAME, "").orEmpty().trim()
        return when (mode) {
            BLACKTUN_MODE_SUBSCRIPTION -> {
                if (username.isBlank()) null else BlackTunSource(
                    mode = BlackTunMode.SUBSCRIPTION,
                    subId = BLACKTUN_SUBSCRIPTION_ID,
                    otherSubId = BLACKTUN_FREE_ID,
                    remarks = "BlackTun $username",
                    url = "",
                    username = username
                )
            }
            BLACKTUN_MODE_FREE -> BlackTunSource(
                mode = BlackTunMode.FREE,
                subId = BLACKTUN_FREE_ID,
                otherSubId = BLACKTUN_SUBSCRIPTION_ID,
                remarks = "BlackTun Free",
                url = BLACKTUN_FREE_URL
            )
            else -> null
        }
    }

    private fun saveBlackTunSource(source: BlackTunSource) {
        MmkvManager.encodeSettings(PREF_BLACKTUN_MODE, source.mode.name.lowercase())
        MmkvManager.encodeSettings(PREF_BLACKTUN_USERNAME, source.username.orEmpty())
    }

    private fun updateBlackTunSourceBadge() {
        val source = currentBlackTunSource()
        blackTunBinding.blacktunSourceBadge.text = when {
            source == null -> getString(R.string.blacktun_not_logged_in)
            source.mode == BlackTunMode.FREE -> "رایگان"
            else -> "کاربر: ${source.username.orEmpty()}"
        }
    }

    private fun showBlackTunLoading(text: String) {
        blackTunBinding.blacktunProgress.visibility = View.VISIBLE
        blackTunBinding.blacktunStatus.text = text
    }

    private fun hideBlackTunLoading() {
        blackTunBinding.blacktunProgress.visibility = View.GONE
        blackTunBinding.blacktunStatus.text = getString(R.string.blacktun_ready)
    }

    private fun showBlackTunMessage(success: Boolean, text: String) {
        blackTunBinding.blacktunMessage.visibility = View.VISIBLE
        blackTunBinding.blacktunMessageCard.visibility = View.VISIBLE
        blackTunBinding.blacktunMessage.text = text
        blackTunBinding.blacktunMessage.setTextColor(ContextCompat.getColor(this, if (success) R.color.blacktun_success else R.color.blacktun_error))
        blackTunBinding.blacktunMessageCard.strokeColor = ContextCompat.getColor(this, if (success) R.color.blacktun_success else R.color.blacktun_error)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)
        if (groups.isEmpty()) {
            tabMediator?.detach()
            tabMediator = null
            binding.tabGroup.isVisible = false
            return
        }

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.size > 1
        refreshGroupTabTitles(true)
    }

    fun refreshGroupTabTitles(refreshAll: Boolean = false) {
        val groupsToRefresh = if (refreshAll || mainViewModel.subscriptionId.isEmpty()) {
            groupPagerAdapter.groups
        } else {
            groupPagerAdapter.groups.filter { it.id == mainViewModel.subscriptionId }
        }

        groupsToRefresh.forEach { group ->
            if (group.id.isEmpty()) {
                return@forEach
            }
            val tabIndex = groupPagerAdapter.groups.indexOfFirst { it.id == group.id }
            if (tabIndex >= 0) {
                val count = MmkvManager.decodeServerList(group.id).size
                binding.tabGroup.getTabAt(tabIndex)?.text = "${group.remarks} ($count)"
            }
        }
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        CoreServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            return
        }

        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true

            blackTunBinding.blacktunConnectButton.text = getString(R.string.blacktun_connected)
            blackTunBinding.blacktunConnectButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.blacktun_green))
            blackTunBinding.blacktunConnectButton.contentDescription = getString(R.string.action_stop_service)
            blackTunBinding.blacktunConnectButton.isEnabled = blackTunConnectJob?.isActive != true
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false

            blackTunBinding.blacktunConnectButton.text = getString(R.string.blacktun_connect)
            blackTunBinding.blacktunConnectButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.blacktun_blue))
            blackTunBinding.blacktunConnectButton.contentDescription = getString(R.string.tasker_start_service)
            blackTunBinding.blacktunConnectButton.isEnabled = blackTunConnectJob?.isActive != true
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_policy_group -> {
            importManually(EConfigType.POLICYGROUP.value)
            true
        }

        R.id.import_manually_proxy_chain -> {
            importManually(EConfigType.PROXYCHAIN.value)
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.locate_selected_config -> {
            locateSelectedServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else if (createConfigType == EConfigType.PROXYCHAIN.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerProxyChainActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            refreshGroupTabTitles()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        blackTunConnectJob?.cancel()
        tabMediator?.detach()
        super.onDestroy()
    }
}
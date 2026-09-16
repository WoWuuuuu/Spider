package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.Protocols
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

abstract class BaseNodeSelectActivity : ThemedActivity(R.layout.layout_node_select),
    ProfileManager.Listener, GroupManager.Listener {

    lateinit var toolbar: MaterialToolbar
    lateinit var progressTest: LinearProgressIndicator
    lateinit var searchInput: EditText
    lateinit var btnClearSearch: ImageView
    lateinit var nodeList: RecyclerView
    lateinit var emptyView: View
    lateinit var emptyText: TextView

    private val adapter = ModernNodeAdapter()
    private var allProfiles: List<ProxyEntity> = emptyList()
    private var allGroups: List<ProxyGroup> = emptyList()
    private var selectedGroupId: Long = 0L
    private var searchKeyword: String = ""
    private var currentOrder: Int = GroupOrder.BY_DELAY
    private var isTesting = false

    open var initialSelectedId: Long = 0L

    @StringRes
    open fun getTitleTextRes(): Int = R.string.select_profile

    abstract fun onProfileSelected(profileId: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        toolbar = findViewById(R.id.toolbar)
        progressTest = findViewById(R.id.progress_test)
        searchInput = findViewById(R.id.search_input)
        btnClearSearch = findViewById(R.id.btn_clear_search)
        nodeList = findViewById(R.id.node_list)
        emptyView = findViewById(R.id.empty_view)
        emptyText = findViewById(R.id.empty_text)
        selectedGroupId = DataStore.currentGroupId()

        toolbar.setTitle(getTitleTextRes())
        toolbar.setNavigationOnClickListener {
            finish()
        }

        toolbar.inflateMenu(R.menu.node_select_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_test_all -> {
                    startSpeedTest()
                    true
                }
                R.id.action_sort -> {
                    showSortDialog()
                    true
                }
                else -> false
            }
        }

        val screenWidthDp = resources.configuration.screenWidthDp
        val spanCount = when {
            screenWidthDp >= 840 -> 3
            screenWidthDp >= 600 -> 2
            else -> 1
        }
        nodeList.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, spanCount)
        nodeList.adapter = adapter

        btnClearSearch.setOnClickListener {
            searchInput.setText("")
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchKeyword = s?.toString()?.trim().orEmpty()
                btnClearSearch.isVisible = searchKeyword.isNotEmpty()
                applyFilter()
            }
        })

        ProfileManager.addListener(this)
        GroupManager.addListener(this)

        loadData()
    }

    override fun onDestroy() {
        super.onDestroy()
        ProfileManager.removeListener(this)
        GroupManager.removeListener(this)
    }

    private fun showSortDialog() {
        val sortOptions = arrayOf(
            getString(R.string.sort_by_delay),
            getString(R.string.sort_by_name)
        )
        val selectedIndex = if (currentOrder == GroupOrder.BY_NAME) 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_sort)
            .setSingleChoiceItems(sortOptions, selectedIndex) { dialog, which ->
                currentOrder = when (which) {
                    1 -> GroupOrder.BY_NAME
                    else -> GroupOrder.BY_DELAY
                }
                applyFilter()
                dialog.dismiss()
            }
            .show()
    }

    fun startSpeedTest() {
        if (isTesting) {
            Snackbar.make(toolbar, R.string.testing_progress, Snackbar.LENGTH_SHORT).show()
            return
        }
        val targets = adapter.getItems().toList()
        if (targets.isEmpty()) return

        isTesting = true
        progressTest.isVisible = true
        progressTest.max = targets.size
        progressTest.progress = 0

        val queue = ConcurrentLinkedQueue(targets)
        val finishedCount = AtomicInteger(0)
        val concurrency = minOf(3, targets.size)

        lifecycleScope.launch(Dispatchers.IO) {
            val jobs = (0 until concurrency).map { workerIndex ->
                launch {
                    if (workerIndex > 0) kotlinx.coroutines.delay(workerIndex * 150L)
                    val tester = UrlTest()
                    while (isActive) {
                        val entity = queue.poll() ?: break
                        try {
                            val ping = tester.doTest(entity)
                            entity.status = 1
                            entity.ping = ping
                        } catch (e: Exception) {
                            Logs.w("UrlTest batch error on #${entity.id}: ${e.message}", e)
                            entity.status = 2
                            entity.ping = -1
                        }
                        ProfileManager.updateProfile(entity)
                        val done = finishedCount.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            progressTest.progress = done
                            adapter.notifyPingChanged(entity.id, entity.ping)
                        }
                    }
                }
            }
            jobs.joinAll()
            withContext(Dispatchers.Main) {
                isTesting = false
                progressTest.isVisible = false
                if (currentOrder == GroupOrder.BY_DELAY) {
                    applyFilter()
                }
            }
        }
    }

    fun testSingleNode(entity: ProxyEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val tester = UrlTest()
            try {
                val ping = tester.doTest(entity)
                entity.status = 1
                entity.ping = ping
            } catch (e: Exception) {
                Logs.w("UrlTest single error on #${entity.id}: ${e.message}", e)
                entity.status = 2
                entity.ping = -1
            }
            ProfileManager.updateProfile(entity)
            withContext(Dispatchers.Main) {
                adapter.notifyPingChanged(entity.id, entity.ping)
                if (currentOrder == GroupOrder.BY_DELAY) {
                    applyFilter()
                }
            }
        }
    }

    fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            allGroups = SagerDatabase.groupDao.allGroups()
            allProfiles = SagerDatabase.proxyDao.getAll().sortedBy { it.userOrder }

            withContext(Dispatchers.Main) {
                val currentGroup = allGroups.find { it.id == selectedGroupId } ?: DataStore.currentGroup()
                toolbar.subtitle = currentGroup.displayName()
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = searchKeyword.lowercase()
        val filtered = allProfiles.filter { entity ->
            val matchGroup = (selectedGroupId == 0L || entity.groupId == selectedGroupId)
            if (!matchGroup) return@filter false

            if (query.isEmpty()) return@filter true

            val name = runCatching { entity.displayName() }.getOrElse { "" }.lowercase()
            val type = runCatching { entity.displayType() }.getOrElse { "" }.lowercase()
            val addr = runCatching { entity.displayAddress() }.getOrElse { "" }.lowercase()

            name.contains(query) || type.contains(query) || addr.contains(query)
        }

        val sorted = when (currentOrder) {
            GroupOrder.BY_NAME -> filtered.sortedBy { runCatching { it.displayName() }.getOrElse { "" }.lowercase() }
            GroupOrder.BY_DELAY -> filtered.sortedWith { a, b ->
                val pa = if (a.ping <= 0) 999999 else a.ping
                val pb = if (b.ping <= 0) 999999 else b.ping
                if (pa != pb) {
                    pa.compareTo(pb)
                } else {
                    a.userOrder.compareTo(b.userOrder)
                }
            }
            else -> filtered.sortedBy { it.userOrder }
        }

        adapter.submitList(sorted)
        val isEmpty = sorted.isEmpty()
        emptyView.isVisible = isEmpty
        nodeList.isVisible = !isEmpty
        if (isEmpty) {
            emptyText.text = getString(R.string.empty_nodes_hint)
        }
    }

    // ProfileManager.Listener
    override suspend fun onAdd(profile: ProxyEntity) {
        onMainDispatcher { loadData() }
    }
    override suspend fun onUpdated(data: TrafficData) = Unit
    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
        onMainDispatcher { loadData() }
    }
    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        onMainDispatcher { loadData() }
    }

    // GroupManager.Listener
    override suspend fun groupAdd(group: ProxyGroup) {
        onMainDispatcher { loadData() }
    }
    override suspend fun groupUpdated(group: ProxyGroup) {
        onMainDispatcher { loadData() }
    }
    override suspend fun groupRemoved(groupId: Long) {
        onMainDispatcher { loadData() }
    }
    override suspend fun groupUpdated(groupId: Long) {
        onMainDispatcher { loadData() }
    }

    inner class ModernNodeAdapter : RecyclerView.Adapter<ModernNodeViewHolder>() {

        private var items: List<ProxyEntity> = emptyList()

        fun getItems(): List<ProxyEntity> = items

        fun submitList(newItems: List<ProxyEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun notifyPingChanged(profileId: Long, ping: Int) {
            val index = items.indexOfFirst { it.id == profileId }
            if (index != -1) {
                items[index].ping = ping
                notifyItemChanged(index)
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModernNodeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_node_item_modern, parent, false)
            return ModernNodeViewHolder(view)
        }

        override fun onBindViewHolder(holder: ModernNodeViewHolder, position: Int) {
            val entity = items[position]
            val isSelected = (initialSelectedId != 0L && entity.id == initialSelectedId) ||
                    (initialSelectedId == 0L && entity.id == DataStore.selectedProxy)

            holder.bind(entity, isSelected,
                onClick = { onProfileSelected(entity.id) },
                onTestClick = { testSingleNode(entity) }
            )
        }
    }

    inner class ModernNodeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.node_card)
        private val nodeName: TextView = view.findViewById(R.id.node_name)
        private val nodeTypeBadge: TextView = view.findViewById(R.id.node_type_badge)
        private val nodeServer: TextView = view.findViewById(R.id.node_server)
        private val nodeDelay: TextView = view.findViewById(R.id.node_delay)
        private val nodeCheck: ImageView = view.findViewById(R.id.node_check)

        fun bind(entity: ProxyEntity, isSelected: Boolean, onClick: () -> Unit, onTestClick: () -> Unit) {
            val name = runCatching { entity.displayName() }.getOrElse { "#${entity.id}" }
            val type = runCatching { entity.displayType() }.getOrElse { "Unknown" }
            val addr = runCatching { entity.displayAddress() }.getOrElse { "" }

            nodeName.text = name
            nodeTypeBadge.text = type
            with(Protocols) {
                nodeTypeBadge.setTextColor(itemView.context.getProtocolColor(entity.type))
            }
            nodeServer.text = addr

            val ping = entity.ping
            if (ping > 0) {
                nodeDelay.text = "${ping} ms"
                nodeDelay.setTextColor(
                    if (ping < 200) Color.parseColor("#4CAF50")
                    else if (ping < 500) Color.parseColor("#FF9800")
                    else Color.parseColor("#F44336")
                )
                nodeDelay.isVisible = true
            } else if (ping == -1) {
                nodeDelay.text = itemView.context.getString(R.string.unavailable)
                nodeDelay.setTextColor(Color.parseColor("#F44336"))
                nodeDelay.isVisible = true
            } else {
                nodeDelay.text = "⚡"
                nodeDelay.setTextColor(Color.parseColor("#808080"))
                nodeDelay.isVisible = true
            }

            nodeDelay.setOnClickListener {
                nodeDelay.text = "..."
                onTestClick()
            }

            val primaryColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimary)

            if (isSelected) {
                card.strokeColor = primaryColor
                card.strokeWidth = (2 * itemView.resources.displayMetrics.density).toInt()
                nodeCheck.isVisible = true
                nodeCheck.imageTintList = ColorStateList.valueOf(primaryColor)
            } else {
                card.strokeColor = Color.TRANSPARENT
                card.strokeWidth = 0
                nodeCheck.isInvisible = true
            }

            card.setOnClickListener {
                onClick()
            }
            card.setOnLongClickListener {
                runCatching {
                    itemView.context.startActivity(
                        entity.settingIntent(itemView.context, false)
                    )
                }
                true
            }
        }
    }
}

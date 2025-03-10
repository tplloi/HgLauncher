package mono.hg.pages

import android.app.Activity.RESULT_OK
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import android.util.SparseArray
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mono.hg.R
import mono.hg.adapters.AppAdapter
import mono.hg.databinding.FragmentAppListBinding
import mono.hg.databinding.LayoutRenameDialogBinding
import mono.hg.databinding.UiLoadProgressBinding
import mono.hg.helpers.LauncherIconHelper
import mono.hg.helpers.PreferenceHelper
import mono.hg.listeners.SimpleScrollListener
import mono.hg.models.App
import mono.hg.receivers.PackageChangesReceiver
import mono.hg.utils.AppUtils
import mono.hg.utils.UserUtils
import mono.hg.utils.Utils
import mono.hg.utils.ViewUtils
import mono.hg.utils.applyAccent
import mono.hg.utils.autoFitColumns
import mono.hg.views.CustomGridLayoutManager
import mono.hg.views.TogglingLinearLayoutManager
import mono.hg.wrappers.DisplayNameComparator
import mono.hg.wrappers.ItemOffsetDecoration
import java.util.*
import kotlin.collections.ArrayList

/**
 * Page displaying an app list.
 * This is the generic implementation of an app list that handles the required features.
 */
class AppsListPage : GenericPage() {
    /*
     * Index of an app that is currently being edited.
     */
    private var editingAppPosition: Int = -1

    /*
     * Adapter for installed apps.
     */
    private val appsAdapter = AppAdapter(ArrayList<App>())

    /*
     * RecyclerView for app list.
     */
    private lateinit var appsRecyclerView: FastScrollRecyclerView

    /*
    * List of excluded apps. These will not be shown in the app list.
    */
    private val excludedAppsList = HashSet<String>()

    /*
     * The list of currently installed and visible packages.
     */
    private var packageNameList = ArrayList<String>()

    /*
     * Package manager; casted through getPackageManager().
     */
    private lateinit var manager: PackageManager

    /*
     * LinearLayoutManager used in appsRecyclerView.
     */
    private lateinit var appsLayoutManager: RecyclerView.LayoutManager

    /*
    * Menu shown when long-pressing apps.
    */
    private var appMenu: PopupMenu? = null

    /*
     * BroadcastReceiver used to receive package changes notification from LauncherActivity.
     */
    private var packageBroadcastReceiver: BroadcastReceiver? = null

    /*
     * Job used to load and populate appsAdapter.
     */
    private var fetchAppsJob: Job? = null

    /*
     * View bindings of this Page.
     */
    private var binding: FragmentAppListBinding? = null
    private var loaderBinding: UiLoadProgressBinding? = null

    private var launcherApps: LauncherApps? = null
    private var userUtils: UserUtils? = null
    private var userManager: UserManager? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentAppListBinding.inflate(inflater, container, false)
        loaderBinding = binding?.root?.let { UiLoadProgressBinding.bind(it) }

        // Get a list of our hidden apps, default to null if there aren't any.
        excludedAppsList.addAll(PreferenceHelper.exclusionList)

        return binding?.root
    }

    override fun onDestroyView() {
        super.onDestroyView()

        unregisterBroadcast()
        binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (Utils.atLeastLollipop()) {
            userManager = requireActivity().getSystemService(Context.USER_SERVICE) as UserManager
        }

        manager = requireActivity().packageManager

        if (Utils.atLeastLollipop()) {
            launcherApps =
                requireActivity().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps?
        }
        userUtils = UserUtils(requireContext())

        registerBroadcast()

        appsLayoutManager = if (PreferenceHelper.useGrid()) {
            // Span will be set with autoFitColumns below.
            CustomGridLayoutManager(requireContext(), 1)
        } else {
            TogglingLinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, true)
        }
        val itemDecoration = ItemOffsetDecoration(requireContext(), R.dimen.item_offset)

        appsRecyclerView = binding!!.appsList.apply {
            setHasFixedSize(true)
            setThumbColor(PreferenceHelper.darkAccent)
            setThumbInactiveColor(PreferenceHelper.accent)
            setPopupBgColor(PreferenceHelper.darkerAccent)
            adapter = appsAdapter.apply {
                setNotifyChangeOfUnfilteredItems(false)
            }
            layoutManager = appsLayoutManager
            itemAnimator = DefaultItemAnimator()
            if (PreferenceHelper.useGrid()) {
                addItemDecoration(itemDecoration)
            }

            // Set the span for GridLayoutManager.
            autoFitColumns(resources.getDimensionPixelSize(R.dimen.grid_item_width))
        }

        // Add long click listener to apps in the apps list.
        // This shows a menu to manage the selected app.
        appsAdapter.addListener(FlexibleAdapter.OnItemLongClickListener { position ->
            appsAdapter.getItem(position)?.apply {
                appsRecyclerView.findViewHolderForLayoutPosition(position)?.itemView?.findViewById<TextView>(
                    R.id.item_name
                )?.let {
                    createAppMenu(it, this)
                }
            }
        })

        appsAdapter.addListener(FlexibleAdapter.OnUpdateListener { size ->
            if (size > 0 && !appsAdapter.isEmpty) {
                loaderBinding?.loader?.hide()
            } else if (!appsAdapter.hasFilter()) {
                loaderBinding?.loader?.show()
            }
        })

        // Listen for app list scroll to hide/show favourites panel.
        // Only do this when the user has favourites panel enabled.
        appsRecyclerView.addOnScrollListener(object : SimpleScrollListener(48) {
            override fun onScrollUp() {
                getLauncherActivity().hidePinnedApps()
            }

            override fun onScroll() {
                appMenu?.menu?.findItem(R.id.action_app_actions)?.subMenu?.close()
                appMenu?.menu?.findItem(SHORTCUT_MENU_GROUP)?.subMenu?.close()
                appMenu?.dismiss()
            }

            override fun onEnd() {
                getLauncherActivity().showPinnedApps()
            }
        })
    }

    override fun onStart() {
        super.onStart()

        if (appsAdapter.isEmpty) {
            viewLifecycleOwner.lifecycleScope.launchWhenStarted {
                fetchAppsJob?.apply {
                    if (this.isCompleted) {
                        appsAdapter.finishedLoading(false)
                        fetchApps()
                    }
                } ?: run {
                    fetchApps()
                }
            }
        }

        // Reset the app list filter.
        resetAppFilter()
    }

    override fun onResume() {
        super.onResume()

        // Create a new package name list to compare against the old package name list
        val newPackageNameList = getPackageNameList(ArrayList())

        // Detect newly installed/removed apps.
        // This check is used when changes occur
        // when the launcher is in the background (i.e, not caught by the receiver).
        viewLifecycleOwner.lifecycleScope.launchWhenResumed {
            withContext(Dispatchers.Default) {
                val mutableAdapterList = ArrayList<App>()

                val start = newPackageNameList.subtract(packageNameList)
                val end = packageNameList.subtract(newPackageNameList)

                // Find the difference in the two lists.
                // Since the new list and the old list can vary in size,
                // we can't really just subtract it. We need to subtract
                // twice then add the differences together.
                start.plus(end).apply {
                    if (this.isNotEmpty()) {
                        mutableAdapterList.addAll(appsAdapter.currentItems.toMutableList())
                    }

                    this.forEach { app ->
                        // There's no need to process ourselves.
                        if (app.contains(requireContext().packageName)) return@forEach

                        // Handle packages changes from another user.
                        val userSplit = app.split("-")
                        val componentName = if (userSplit.size == 2) userSplit[1].trim() else app
                        val user = try {
                            if (userSplit.size == 2) userSplit[0].trim()
                                .toLong() else userUtils?.currentSerial
                                ?: 0
                        } catch (w: NumberFormatException) {
                            0 // Return the default serial number if we can't get any.
                        }

                        // First, check if this user & component name combination
                        // exists in the current list. This is because our list
                        // won't be notified on an app update. There can only
                        // be two states: installing or removing.
                        //
                        // If retainAll doesn't return a true, then assume
                        // that the app is a new one.
                        if (!mutableAdapterList.retainAll { it.userPackageName != app }) {
                            manager.getLaunchIntentForPackage(
                                AppUtils.getPackageName(componentName)
                            )?.apply {
                                addApp(mutableAdapterList, componentName, user)
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (mutableAdapterList.isNotEmpty()) {
                            appsAdapter.updateDataSet(
                                mutableAdapterList.sortedWith(
                                    DisplayNameComparator(PreferenceHelper.isListInverted)
                                ), true
                            )
                        }
                    }

                    // Update the internal list.
                    getPackageNameList(packageNameList)
                    AppUtils.updatePackageCount(manager)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Update the icon on the selected app.
        if (resultCode == RESULT_OK && requestCode == SET_ICON_REQUEST && data != null) {
            appsAdapter.getItem(editingAppPosition)?.apply {
                LauncherIconHelper.cacheIcon(
                    requireContext(),
                    data,
                    "",
                    AppUtils.getPackageName(packageName),
                    user
                )?.let { icon = it }
            }?.let { appsAdapter.updateItem(it) }

            // Reset the index once we're done.
            editingAppPosition = -1
        }
    }

    override fun isAcceptingSearch(): Boolean {
        return true
    }

    override fun commitSearch(query: String) {
        appsAdapter.setFilter(query)
        appsAdapter.filterItems()
    }

    override fun launchPreselection(): Boolean {
        return if (!appsAdapter.isEmpty) {
            appsRecyclerView.let {
                ViewUtils.keyboardLaunchApp(requireActivity(), it, appsAdapter)
            }
            true
        } else {
            false
        }
    }

    /**
     * Creates a PopupMenu to use in a long-pressed app object.
     *
     * @param view     View for the PopupMenu to anchor to.
     * @param app      App object selected from the list.
     */
    private fun createAppMenu(view: View, app: App) {
        val packageName = app.packageName
        val user = app.user
        val packageNameUri = Uri.fromParts("package", AppUtils.getPackageName(packageName), null)
        val shortcutMap = SparseArray<String>()
        val position = appsAdapter.getGlobalPositionOf(app)

        appMenu = ViewUtils.createAppMenu(requireActivity(), view, app).apply {
            // Inflate app shortcuts.
            if (Utils.sdkIsAround(25)) {
                var menuId = SHORTCUT_MENU_GROUP
                AppUtils.getShortcuts(launcherApps, packageName)?.forEach {
                    shortcutMap.put(menuId, it.id)
                    menu
                        .findItem(SHORTCUT_MENU_GROUP)
                        .subMenu
                        ?.add(SHORTCUT_MENU_GROUP, menuId, Menu.NONE, it.shortLabel)
                    menuId++
                }
                menu.getItem(0).isVisible = shortcutMap.size() > 0
            } else {
                menu.getItem(0).isVisible = false
            }

            gravity = Gravity.NO_GRAVITY
            show()
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_icon_set -> {
                        // Update the index first.
                        editingAppPosition = appsAdapter.getGlobalPositionOf(app)

                        // Load the picker intent.
                        // TODO: We should use more intent actions here.
                        Intent("org.adw.launcher.icons.ACTION_PICK_ICON").apply {
                            startActivityForResult(
                                Intent.createChooser(
                                    this, getString(R.string.dialog_title_set_icon)
                                ), SET_ICON_REQUEST
                            )
                        }
                    }
                    R.id.action_icon_reset -> {
                        // Reset the icon cache first.
                        LauncherIconHelper.deleteCachedIcon(
                            requireContext(),
                            "",
                            app.packageName,
                            app.user
                        )

                        // Retrieve a brand new icon.
                        app.icon = LauncherIconHelper.getIcon(
                            requireActivity(),
                            app.userPackageName,
                            app.user,
                            false
                        )
                        appsAdapter.updateItem(app)
                    }
                    R.id.action_pin -> getLauncherActivity().pinAppHere(app.userPackageName, user)
                    R.id.action_info -> AppUtils.openAppDetails(
                        requireActivity(),
                        packageName,
                        user
                    )
                    R.id.action_uninstall -> AppUtils.uninstallApp(
                        requireActivity(),
                        packageNameUri
                    )
                    R.id.action_shorthand -> buildShorthandDialog(position)
                    R.id.action_hide -> hideApp(position)
                    else -> {
                        // Catch click actions from the shortcut menu group.
                        if (item.groupId == SHORTCUT_MENU_GROUP) {
                            userUtils?.getUser(user)?.let {
                                AppUtils.launchShortcut(
                                    it,
                                    launcherApps,
                                    packageName,
                                    shortcutMap[item.itemId]
                                )
                            }
                        }
                    }
                }
                true
            }
        }
    }

    private fun registerBroadcast() {
        packageBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.getStringExtra("package")
                val action = intent.getIntExtra("action", 42)
                val launchIntent = packageName?.let {
                    requireActivity().packageManager.getLaunchIntentForPackage(
                        it
                    )
                }

                // End early if it has anything to do with us.
                if (!packageName.isNullOrEmpty() && packageName.contains(requireContext().packageName)) return

                launchIntent?.component?.apply {
                    val hasLauncherCategory = launchIntent.hasCategory(Intent.CATEGORY_LAUNCHER)

                    val user = userUtils?.currentSerial ?: 0
                    val componentName = this.flattenToString()

                    // If the intent has no launcher category, then it may mean that
                    // this intent is meant for an uninstalled/removed package,
                    // or it can be meant for an app without a launcher activity.
                    // Either way, we don't want to fetch anything if that is the case.
                    if (hasLauncherCategory && appsAdapter.hasFinishedLoading()) {
                        // Add our new app to the list.
                        viewLifecycleOwner.lifecycleScope.launch {
                            val newList: MutableList<App> = appsAdapter.currentItems.toMutableList()
                            withContext(Dispatchers.Default) {
                                addApp(newList, componentName, user)
                            }
                            appsAdapter.updateDataSet(newList, true)
                        }
                    }
                } ?: run {
                    // If the app is being uninstalled, it will not have
                    // a launch intent, so it's safe to remove it from the list.
                    if (action == PackageChangesReceiver.PACKAGE_REMOVED) {
                        packageName?.apply {
                            appsAdapter.updateDataSet(appsAdapter.currentItems.filterNot {
                                it.packageName == this
                            }, true)
                        }
                    }
                }

                // We should recount here, regardless of whether we update the list or not.
                getPackageNameList(packageNameList)
                AppUtils.updatePackageCount(requireActivity().packageManager)
            }
        }

        // We want this fragment to receive the package change broadcast,
        // since otherwise it won't be notified when there are changes to that.
        IntentFilter().apply {
            addAction("mono.hg.PACKAGE_CHANGE_BROADCAST")
        }.also {
            requireActivity().registerReceiver(packageBroadcastReceiver, it)
        }
    }

    private fun unregisterBroadcast() {
        packageBroadcastReceiver?.apply {
            requireActivity().unregisterReceiver(packageBroadcastReceiver)
            packageBroadcastReceiver = null
        } ?: run {
            Utils.sendLog(
                Utils.LogLevel.VERBOSE,
                "unregisterBroadcast() was called to a null receiver."
            )
        }
    }

    private fun fetchApps() {
        fetchAppsJob = viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            appsAdapter.updateDataSet(withContext(Dispatchers.Default) {
                getPackageNameList(packageNameList)
                AppUtils.loadApps(requireActivity(), true)
            }, true)
            appsAdapter.finishedLoading(true)

            // Always update the package count when retrieving apps.
            AppUtils.updatePackageCount(manager)
        }
    }

    private fun hideApp(positionInAdapter: Int) {
        appsAdapter.getItem(positionInAdapter)?.apply {
            // Add the app's package name to the exclusion list.
            excludedAppsList.add(userPackageName)
            PreferenceHelper.update("hidden_apps", excludedAppsList)

            // Reload the app list!
            appsAdapter.removeItem(positionInAdapter)
        }
    }

    private fun addApp(list: MutableList<App>, componentName: String, user: Long) {
        // Don't add the app if it has the launcher's package name or if it's hidden.
        if (componentName.contains(requireContext().packageName) ||
            excludedAppsList.contains(componentName)
        ) {
            return
        }

        with(list) {
            // If there's an app with a matching componentName,
            // then it's probably the same app. Update that entry instead
            // of adding a new app.
            this.find { it.userPackageName == "${user}-${componentName}" }?.apply {
                appName = AppUtils.getPackageLabel(
                    requireActivity().packageManager,
                    componentName
                )
                userPackageName = AppUtils.appendUser(user, componentName)
                icon = LauncherIconHelper.getIcon(
                    requireActivity(),
                    componentName,
                    user,
                    PreferenceHelper.shouldHideIcon()
                )
            } ?: run {
                App(componentName, user).apply {
                    appName = AppUtils.getPackageLabel(
                        requireActivity().packageManager,
                        componentName
                    )
                    userPackageName = AppUtils.appendUser(user, componentName)
                    icon = LauncherIconHelper.getIcon(
                        requireActivity(),
                        componentName,
                        user,
                        PreferenceHelper.shouldHideIcon()
                    )
                }.also {
                    // We need to use currentItems here because
                    // using the default list would basically create a filter.
                    this.apply {
                        // Don't add the new app if we already have it.
                        // This probably caused by two receivers firing at once.
                        if (!this.contains(it)) {
                            add(it)

                            // Re-sort to make sure we have the list in proper order.
                            sortWith(DisplayNameComparator(PreferenceHelper.isListInverted))
                        }
                    }
                }
            }
        }
    }

    private fun resetAppFilter() {
        if (appsAdapter.hasFilter()) {
            appsAdapter.setFilter("")
            appsAdapter.filterItems()
        }
    }

    private fun getPackageNameList(list: MutableList<String>): List<String> {
        // Clear the list first, since mapTo() will duplicate the contents.
        list.clear()

        // Get the list of package names.
        if (Utils.atLeastLollipop()) {
            // Handle multiple user scenario here.
            userManager?.apply {
                this.userProfiles.forEach { profile ->
                    launcherApps?.getActivityList(null, profile)?.filterNot {
                        it.componentName.flattenToString().contains(requireContext().packageName)
                    }?.mapTo(list) {
                        "${userUtils?.getSerial(profile)}-${it.componentName.flattenToString()}"
                    }
                }
            }
        } else {
            Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }.also {
                return manager.queryIntentActivities(it, 0)
                    .filterNot { app -> app.activityInfo.packageName.contains(requireContext().packageName) }
                    .mapTo(list) { resolve ->
                        "${userUtils?.currentSerial}-${resolve.activityInfo.packageName}/${resolve.activityInfo.name}"
                    }
            }
        }

        // Return the sorted list.
        return list.sorted()
    }

    /**
     * Creates a dialog to set an app's shorthand.
     * @param position    Adapter position of the app.
     */
    private fun buildShorthandDialog(position: Int) {
        val binding = LayoutRenameDialogBinding.inflate(layoutInflater)
        val packageName = appsAdapter.getItem(position)?.packageName
        val hasHintName = appsAdapter.getItem(position)?.hasHintName() ?: false
        val renameField = binding.renameField.apply {
            ViewCompat.setBackgroundTintList(this, ColorStateList.valueOf(PreferenceHelper.accent))
            packageName?.let {
                setText(PreferenceHelper.getLabel(it))
                hint = text
            }
        }

        with(AlertDialog.Builder(requireContext())) {
            setView(binding.root)
            setTitle(R.string.dialog_title_shorthand)
            if (hasHintName) {
                setNeutralButton(R.string.action_web_provider_remove) { _, _ ->
                    appsAdapter.getItem(position)?.apply { hintName = "" }
                    packageName?.let { PreferenceHelper.updateLabel(it, "", true) }
                }
            }
            setNegativeButton(R.string.dialog_cancel, null)
            setPositiveButton(R.string.dialog_ok) { _, _ ->
                val newLabel = renameField.text
                    .toString()
                    .replace("|", "")
                    .trim { it <= ' ' }

                // Update the specified item.
                if (newLabel.isNotBlank()) {
                    appsAdapter.getItem(position)?.apply { hintName = newLabel }
                    packageName?.let { PreferenceHelper.updateLabel(it, newLabel, false) }
                }
            }

            create().apply {
                show()
                applyAccent()
            }
        }
    }

    companion object {
        private const val SHORTCUT_MENU_GROUP = 247
        private const val SET_ICON_REQUEST = 8000
    }
}
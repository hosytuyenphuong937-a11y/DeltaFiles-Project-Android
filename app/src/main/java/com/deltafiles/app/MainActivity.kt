package com.deltafiles.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.deltafiles.app.databinding.ActivityMainBinding
import com.deltafiles.app.databinding.DialogNameBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter
    private lateinit var prefs: SharedPreferences

    private val rootDir: File = Environment.getExternalStorageDirectory()
    private var currentDir: File = rootDir

    private var showHidden = false
    private var sortMode = SortMode.NAME
    private var accessGranted = false

    private var allItems: List<Entry> = emptyList()
    private var listUnreadable = false
    private var loadGeneration = 0
    private var resetScroll = false

    private var query = ""
    private var searchActive = false
    private var searchItem: MenuItem? = null

    private val selection = LinkedHashSet<String>()
    private var clipboard: List<File> = emptyList()
    private var clipboardCut = false

    private val legacyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            onAccessChanged()
        }

    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onAccessChanged()
        }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = getSharedPreferences("delta_prefs", MODE_PRIVATE)
        showHidden = prefs.getBoolean("show_hidden", false)
        sortMode = SortMode.values().getOrElse(prefs.getInt("sort_mode", 0)) { SortMode.NAME }

        val savedDir = savedInstanceState?.getString("dir")?.let { File(it) }
        if (savedDir != null && savedDir.isDirectory) currentDir = savedDir

        adapter = FileAdapter(
            onClick = { onItemClick(it) },
            onLongClick = { onItemLongClick(it) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener { showCreateDialog() }
        binding.grantButton.setOnClickListener { requestAccess() }
        binding.pasteButton.setOnClickListener { pasteHere() }
        binding.cancelPasteButton.setOnClickListener {
            clipboard = emptyList()
            updatePasteBar()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selection.isNotEmpty() -> clearSelection()
                    searchActive -> searchItem?.collapseActionView()
                    canGoUp() -> navigateTo(currentDir.parentFile!!)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        onAccessChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("dir", currentDir.absolutePath)
    }

    // ---------------------------------------------------------------------------------------
    // Storage access
    // ---------------------------------------------------------------------------------------

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                allFilesLauncher.launch(appIntent)
            } catch (e: Exception) {
                allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun onAccessChanged() {
        accessGranted = hasAccess()
        binding.permissionPanel.visibility = if (accessGranted) View.GONE else View.VISIBLE
        binding.fab.visibility = if (accessGranted) View.VISIBLE else View.GONE
        if (accessGranted) {
            loadDir()
        } else {
            allItems = emptyList()
            render()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Listing and rendering
    // ---------------------------------------------------------------------------------------

    private fun isRoot(): Boolean = currentDir.absolutePath == rootDir.absolutePath

    private fun canGoUp(): Boolean = !isRoot() && currentDir.parentFile != null

    private fun loadDir() {
        if (!accessGranted) return
        val generation = ++loadGeneration
        val dir = currentDir
        val hidden = showHidden
        val mode = sortMode
        thread {
            val listed = dir.listFiles()
            val entries = listed
                ?.filter { hidden || !it.name.startsWith(".") }
                ?.map { FileOps.snapshot(it) }
                ?.sortedWith(FileOps.comparator(mode))
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                listUnreadable = listed == null
                allItems = entries ?: emptyList()
                val selectionBefore = selection.size
                selection.retainAll(allItems.map { it.path }.toSet())
                render()
                if (resetScroll) {
                    binding.recycler.scrollToPosition(0)
                    resetScroll = false
                }
                if (selection.size != selectionBefore) invalidateOptionsMenu()
            }
        }
    }

    private fun visibleItems(): List<Entry> =
        if (query.isBlank()) allItems else allItems.filter { it.name.contains(query, ignoreCase = true) }

    private fun render() {
        val visible = visibleItems()
        adapter.submit(visible, selection)

        binding.emptyView.text = getString(
            when {
                listUnreadable -> R.string.error_unreadable
                query.isNotBlank() -> R.string.no_results
                else -> R.string.empty_folder
            }
        )
        binding.emptyView.visibility = if (accessGranted && visible.isEmpty()) View.VISIBLE else View.GONE

        updateBreadcrumb()
        updateToolbar()
        updatePasteBar()
    }

    private fun updateToolbar() {
        val bar = supportActionBar ?: return
        val selecting = selection.isNotEmpty()
        if (selecting) {
            bar.title = getString(R.string.n_selected, selection.size)
            bar.subtitle = null
        } else {
            bar.title = if (isRoot()) getString(R.string.internal_storage) else currentDir.name
            bar.subtitle = getString(R.string.free_space, Formatter.formatFileSize(this, rootDir.freeSpace))
        }
        bar.setDisplayHomeAsUpEnabled(selecting || canGoUp())
        bar.setHomeAsUpIndicator(if (selecting) R.drawable.ic_close else R.drawable.ic_arrow_back)
    }

    private fun updateBreadcrumb() {
        binding.breadcrumb.removeAllViews()

        val chain = ArrayList<File>()
        var f: File? = currentDir
        while (f != null) {
            chain.add(0, f)
            if (f.absolutePath == rootDir.absolutePath) break
            f = f.parentFile
        }

        for ((index, dir) in chain.withIndex()) {
            if (index > 0) addSeparator()
            val label = if (dir.absolutePath == rootDir.absolutePath) {
                getString(R.string.internal_storage)
            } else {
                dir.name
            }
            addCrumb(label, dir, index == chain.lastIndex)
        }

        binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun addSeparator() {
        val tv = TextView(this)
        tv.text = "›"
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        tv.setTextColor(resolveColor(android.R.attr.textColorSecondary))
        binding.breadcrumb.addView(tv)
    }

    private fun addCrumb(label: String, target: File, current: Boolean) {
        val tv = TextView(this)
        tv.text = label
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        tv.setPadding(dp(8), dp(6), dp(8), dp(6))
        if (current) {
            tv.setTextColor(resolveColor(android.R.attr.textColorPrimary))
            tv.setTypeface(tv.typeface, Typeface.BOLD)
        } else {
            tv.setTextColor(ContextCompat.getColor(this, R.color.delta_primary))
            tv.setBackgroundResource(selectableBackgroundRes())
            tv.setOnClickListener { navigateTo(target) }
        }
        binding.breadcrumb.addView(tv)
    }

    private fun updatePasteBar() {
        if (clipboard.isEmpty()) {
            binding.pasteBar.visibility = View.GONE
            return
        }
        binding.pasteText.text = getString(
            if (clipboardCut) R.string.clipboard_move else R.string.clipboard_copy,
            clipboard.size
        )
        binding.pasteBar.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------------

    private fun navigateTo(dir: File) {
        val hadSelection = selection.isNotEmpty()
        selection.clear()
        if (searchActive) searchItem?.collapseActionView()
        query = ""
        currentDir = dir
        resetScroll = true
        if (hadSelection) invalidateOptionsMenu()
        loadDir()
    }

    private fun onItemClick(e: Entry) {
        when {
            selection.isNotEmpty() -> toggleSelection(e)
            e.isDir -> navigateTo(e.file)
            else -> openFile(e.file)
        }
    }

    private fun onItemLongClick(e: Entry) {
        toggleSelection(e)
    }

    // ---------------------------------------------------------------------------------------
    // Selection
    // ---------------------------------------------------------------------------------------

    private fun toggleSelection(e: Entry) {
        if (selection.isEmpty() && searchActive) searchItem?.collapseActionView()
        if (!selection.remove(e.path)) selection.add(e.path)
        onSelectionChanged()
    }

    private fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        onSelectionChanged()
    }

    private fun selectAll() {
        for (e in visibleItems()) selection.add(e.path)
        onSelectionChanged()
    }

    private fun onSelectionChanged() {
        render()
        invalidateOptionsMenu()
    }

    private fun selectedFiles(): List<File> =
        allItems.filter { selection.contains(it.path) }.map { it.file }

    // ---------------------------------------------------------------------------------------
    // Menu
    // ---------------------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        val item = menu.findItem(R.id.action_search)
        searchItem = item
        val searchView = item?.actionView as? SearchView
        searchView?.queryHint = getString(R.string.search_hint)
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean = true

            override fun onQueryTextChange(newText: String?): Boolean {
                query = newText.orEmpty()
                render()
                return true
            }
        })
        item?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(it: MenuItem): Boolean {
                searchActive = true
                return true
            }

            override fun onMenuItemActionCollapse(it: MenuItem): Boolean {
                searchActive = false
                query = ""
                render()
                return true
            }
        })
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val selecting = selection.isNotEmpty()
        menu.setGroupVisible(R.id.group_browse, !selecting)
        menu.setGroupVisible(R.id.group_select, selecting)
        menu.findItem(R.id.action_hidden)?.isChecked = showHidden
        menu.findItem(R.id.action_rename)?.isVisible = selecting && selection.size == 1
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (selection.isNotEmpty()) {
                    clearSelection()
                } else if (canGoUp()) {
                    navigateTo(currentDir.parentFile!!)
                }
            }
            R.id.action_sort -> showSortDialog()
            R.id.action_hidden -> {
                showHidden = !showHidden
                item.isChecked = showHidden
                prefs.edit().putBoolean("show_hidden", showHidden).apply()
                loadDir()
            }
            R.id.action_home -> navigateTo(rootDir)
            R.id.action_copy -> copySelected(cut = false)
            R.id.action_cut -> copySelected(cut = true)
            R.id.action_delete -> confirmDelete()
            R.id.action_share -> shareSelected()
            R.id.action_rename -> promptRename()
            R.id.action_select_all -> selectAll()
            R.id.action_details -> showDetails()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, FileOps.mimeOf(file))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.error_no_app)
        } catch (e: Exception) {
            toast(R.string.error_open)
        }
    }

    private fun shareSelected() {
        val files = selectedFiles().filter { it.isFile }
        if (files.isEmpty()) {
            toast(R.string.share_no_files)
            return
        }
        try {
            val uris = files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) }
            val intent: Intent
            if (uris.size == 1) {
                intent = Intent(Intent.ACTION_SEND)
                intent.type = FileOps.mimeOf(files[0])
                intent.putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                intent = Intent(Intent.ACTION_SEND_MULTIPLE)
                intent.type = "*/*"
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (e: Exception) {
            toast(R.string.error_share)
        }
    }

    private fun copySelected(cut: Boolean) {
        val files = selectedFiles()
        if (files.isEmpty()) return
        clipboard = files
        clipboardCut = cut
        clearSelection()
        updatePasteBar()
    }

    private fun pasteHere() {
        val items = clipboard
        val cut = clipboardCut
        val dest = currentDir
        if (items.isEmpty()) return
        runBusy(
            if (cut) R.string.moving else R.string.copying,
            { if (cut) FileOps.moveAll(items, dest) else FileOps.copyAll(items, dest) }
        ) { failed ->
            if (cut) {
                clipboard = emptyList()
                updatePasteBar()
            }
            toastResult(failed)
        }
    }

    private fun confirmDelete() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(resources.getQuantityString(R.plurals.delete_message, files.size, files.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                selection.clear()
                invalidateOptionsMenu()
                runBusy(
                    R.string.deleting,
                    { files.count { !FileOps.deleteTree(it) } }
                ) { failed -> toastResult(failed) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptRename() {
        val file = selectedFiles().singleOrNull() ?: return
        promptName(R.string.rename, file.name) { newName ->
            val parent = file.parentFile
            val err = validateName(newName, parent, file)
            if (err != null) {
                toast(err)
                return@promptName
            }
            val target = File(parent, newName)
            if (file.renameTo(target)) {
                selection.clear()
                invalidateOptionsMenu()
                loadDir()
            } else {
                toast(R.string.error_rename)
            }
        }
    }

    private fun showCreateDialog() {
        val options = arrayOf(getString(R.string.new_folder), getString(R.string.new_file))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_item)
            .setItems(options) { _, which ->
                if (which == 0) createFolder() else createFile()
            }
            .show()
    }

    private fun createFolder() {
        promptName(R.string.new_folder, "") { name ->
            val err = validateName(name, currentDir, null)
            if (err != null) {
                toast(err)
                return@promptName
            }
            if (File(currentDir, name).mkdirs()) loadDir() else toast(R.string.error_create)
        }
    }

    private fun createFile() {
        promptName(R.string.new_file, "") { name ->
            val err = validateName(name, currentDir, null)
            if (err != null) {
                toast(err)
                return@promptName
            }
            val ok = try {
                File(currentDir, name).createNewFile()
            } catch (e: IOException) {
                false
            }
            if (ok) loadDir() else toast(R.string.error_create)
        }
    }

    private fun showSortDialog() {
        val labels = arrayOf(
            getString(R.string.sort_name),
            getString(R.string.sort_date),
            getString(R.string.sort_size),
            getString(R.string.sort_type)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(labels, sortMode.ordinal) { dialog, which ->
                sortMode = SortMode.values()[which]
                prefs.edit().putInt("sort_mode", which).apply()
                dialog.dismiss()
                loadDir()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDetails() {
        val files = selectedFiles()
        if (files.isEmpty()) return

        val header: String = if (files.size == 1) {
            val f = files[0]
            val type = if (f.isDirectory) getString(R.string.folder) else FileOps.mimeOf(f)
            val modified = DateFormat.getDateTimeInstance().format(Date(f.lastModified()))
            listOf(
                getString(R.string.detail_name, f.name),
                getString(R.string.detail_location, f.parent ?: ""),
                getString(R.string.detail_type, type),
                getString(R.string.detail_modified, modified)
            ).joinToString("\n")
        } else {
            getString(R.string.detail_selected_count, files.size)
        }

        val initialText = header + "\n" + getString(R.string.detail_size, getString(R.string.detail_calculating))
        val tv = TextView(this)
        tv.setPadding(dp(24), dp(8), dp(24), 0)
        tv.setTextIsSelectable(true)
        tv.text = initialText

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.details_title)
            .setView(tv)
            .setPositiveButton(R.string.ok, null)
            .show()

        thread {
            val total = files.sumOf { FileOps.treeSize(it) }
            runOnUiThread {
                if (dialog.isShowing) {
                    val sizeText = Formatter.formatFileSize(this, total)
                    tv.text = header + "\n" + getString(R.string.detail_size, sizeText)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Runs [work] off the main thread behind a small blocking dialog; [work] returns the failure count. */
    private fun runBusy(messageRes: Int, work: () -> Int, onResult: (Int) -> Unit) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(messageRes)
            .setCancelable(false)
            .create()
        dialog.show()
        thread {
            val failures = try {
                work()
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialog.dismiss()
                onResult(failures)
                loadDir()
            }
        }
    }

    private fun promptName(titleRes: Int, initial: String, onOk: (String) -> Unit) {
        val dlg = DialogNameBinding.inflate(layoutInflater)
        dlg.input.setText(initial)
        val dot = initial.lastIndexOf('.')
        dlg.input.setSelection(0, if (dot > 0) dot else initial.length)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(dlg.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                onOk(dlg.input.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dlg.input.requestFocus()
    }

    /** Returns a string resource describing what's wrong with [name], or null if it is fine. */
    private fun validateName(name: String, parent: File?, self: File?): Int? {
        if (name.isEmpty()) return R.string.error_empty_name
        if (name.contains('/') || name == "." || name == "..") return R.string.error_invalid_name
        if (parent != null) {
            val target = File(parent, name)
            if (target.exists() && target.canonicalPath != self?.canonicalPath) {
                return R.string.error_exists
            }
        }
        return null
    }

    private fun toastResult(failed: Int) {
        when {
            failed == 0 -> toast(R.string.done)
            failed < 0 -> toast(R.string.operation_failed)
            else -> toast(resources.getQuantityString(R.plurals.items_failed, failed, failed))
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
    }

    private fun selectableBackgroundRes(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }
}

package net.smoreo.thortweaks.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.smoreo.thortweaks.R
import net.smoreo.thortweaks.util.RootShell
import java.io.BufferedReader
import java.io.InputStreamReader

class TaskDrawerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TaskDrawer"
    }

    data class RecentTask(
        val taskId: Int,
        val packageName: String,
        val label: String,
        val icon: Drawable?
    )

    private lateinit var rvTasks: RecyclerView
    private lateinit var tvEmpty: TextView
    private var tasks = mutableListOf<RecentTask>()
    private lateinit var adapter: TaskAdapter

    private var launchTime = 0L
    private var lastStickNav = 0L
    private val stickNavCooldown = 300L
    private val stickDeadzone = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchTime = SystemClock.uptimeMillis()
        setContentView(R.layout.activity_task_drawer)

        rvTasks = findViewById(R.id.rvTasks)
        tvEmpty = findViewById(R.id.tvEmpty)

        adapter = TaskAdapter(tasks,
            onOpen = { task -> switchToTask(task) },
            onDismiss = { task -> dismissTask(task) }
        )

        rvTasks.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvTasks.adapter = adapter

        btnScreenshot = findViewById(R.id.btnScreenshot)
        btnClearAll = findViewById(R.id.btnClearAll)
        btnScreenshot.setOnClickListener { takeScreenshot() }
        btnClearAll.setOnClickListener { clearAllTasks() }

        // Close on background tap
        findViewById<View>(android.R.id.content).setOnClickListener { finish() }

        loadRecentTasks()
    }

    private lateinit var btnScreenshot: View
    private lateinit var btnClearAll: View

    private fun moveFocusVertical(direction: Int): Boolean {
        val focused = currentFocus
        if (focused == null) {
            rvTasks.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            return true
        }
        // From cards, go down to buttons
        if (direction == View.FOCUS_DOWN) {
            if (focused.parent == rvTasks || focused == rvTasks) {
                btnScreenshot.requestFocus()
                return true
            }
        }
        // From buttons, go up to cards
        if (direction == View.FOCUS_UP) {
            if (focused == btnScreenshot || focused == btnClearAll) {
                val pos = getFocusedTaskPos()
                val target = if (pos >= 0) pos else 0
                rvTasks.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                return true
            }
        }
        return false
    }

    private fun getFocusedTaskPos(): Int {
        val focused = rvTasks.findFocus() ?: return -1
        return rvTasks.getChildAdapterPosition(focused)
    }

    private fun navigateHorizontal(delta: Int): Boolean {
        val focused = currentFocus
        // If on buttons, move between them
        if (focused == btnScreenshot && delta > 0) {
            btnClearAll.requestFocus()
            return true
        }
        if (focused == btnClearAll && delta < 0) {
            btnScreenshot.requestFocus()
            return true
        }
        // Otherwise navigate cards
        return navigateCards(delta)
    }

    private fun navigateCards(delta: Int): Boolean {
        val pos = getFocusedTaskPos()
        val target = if (pos < 0) 0 else (pos + delta).coerceIn(0, tasks.size - 1)
        if (tasks.isEmpty()) return true
        rvTasks.scrollToPosition(target)
        rvTasks.post {
            rvTasks.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != 0 &&
            event.action == MotionEvent.ACTION_MOVE) {

            if (SystemClock.uptimeMillis() - launchTime < 500) return true

            val now = SystemClock.uptimeMillis()
            if (now - lastStickNav < stickNavCooldown) return true

            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

            val handled = when {
                x < -stickDeadzone || hatX < -stickDeadzone -> navigateHorizontal(-1)
                x > stickDeadzone || hatX > stickDeadzone -> navigateHorizontal(1)
                y < -stickDeadzone || hatY < -stickDeadzone -> moveFocusVertical(View.FOCUS_UP)
                y > stickDeadzone || hatY > stickDeadzone -> moveFocusVertical(View.FOCUS_DOWN)
                else -> false
            }

            if (handled) lastStickNav = now
            return handled || super.onGenericMotionEvent(event)
        }
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Swallow ALL key events during grace period (including HOME which
        // the framework handles before onKeyDown)
        if (SystemClock.uptimeMillis() - launchTime < 500) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {

        when (keyCode) {
            // D-pad navigation
            KeyEvent.KEYCODE_DPAD_LEFT -> return navigateHorizontal(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return navigateHorizontal(1)
            KeyEvent.KEYCODE_DPAD_UP -> return moveFocusVertical(View.FOCUS_UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> return moveFocusVertical(View.FOCUS_DOWN)
            // A button — activate focused element
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                val focused = currentFocus
                if (focused == btnScreenshot) { takeScreenshot(); return true }
                if (focused == btnClearAll) { clearAllTasks(); return true }
                val pos = getFocusedTaskPos()
                if (pos >= 0 && pos < tasks.size) {
                    switchToTask(tasks[pos])
                }
                return true
            }
            // Y button — dismiss focused task
            KeyEvent.KEYCODE_BUTTON_Y -> {
                val pos = getFocusedTaskPos()
                if (pos >= 0 && pos < tasks.size) {
                    dismissTask(tasks[pos])
                }
                return true
            }
            // B button — close drawer
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
            // L1 — screenshot
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                takeScreenshot()
                return true
            }
            // R1 — clear all
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                clearAllTasks()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun loadRecentTasks() {
        Thread {
            val recentTasks = queryRecentTasks()
            runOnUiThread {
                tasks.clear()
                tasks.addAll(recentTasks)
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
                rvTasks.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE

                // Focus the first card
                rvTasks.post {
                    val first = rvTasks.findViewHolderForAdapterPosition(0)
                    first?.itemView?.requestFocus()
                }
            }
        }.start()
    }

    private fun queryRecentTasks(): List<RecentTask> {
        val result = mutableListOf<RecentTask>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys activity recents"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val lines = reader.readLines()
            p.waitFor()

            // Parse "Recent #N: Task{hash #taskId type=TYPE A=uid:pkg}"
            val taskPattern = Regex("""Recent #\d+: Task\{[^ ]+ #(\d+) type=(\w+) (?:A=\d+:)?([^}]+)\}""")

            for (line in lines) {
                val match = taskPattern.find(line.trim()) ?: continue
                val taskId = match.groupValues[1].toIntOrNull() ?: continue
                val type = match.groupValues[2]
                val pkg = match.groupValues[3].trim()

                // Skip home, recents, and our own app
                if (type == "home" || type == "recents") continue
                if (pkg == "net.smoreo.thortweaks") continue
                if (pkg == "com.android.settings.FallbackHome") continue

                // Get app info
                val (label, icon) = getAppInfo(pkg)
                result.add(RecentTask(taskId, pkg, label, icon))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query tasks", e)
        }
        return result
    }

    private fun getAppInfo(packageName: String): Pair<String, Drawable?> {
        return try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            val label = packageManager.getApplicationLabel(ai).toString()
            val icon = packageManager.getApplicationIcon(ai)
            Pair(label, icon)
        } catch (_: PackageManager.NameNotFoundException) {
            Pair(packageName.substringAfterLast('.'), null)
        }
    }

    private fun switchToTask(task: RecentTask) {
        finish()
        Thread {
            RootShell.cmd("monkey -p ${task.packageName} -c android.intent.category.LAUNCHER 1")
        }.start()
    }

    private fun dismissTask(task: RecentTask) {
        Thread {
            RootShell.cmd("am stack remove ${task.taskId}")
        }.start()
        val pos = tasks.indexOf(task)
        if (pos >= 0) {
            tasks.removeAt(pos)
            adapter.notifyItemRemoved(pos)
            if (tasks.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvTasks.visibility = View.GONE
            } else {
                // Focus next card
                rvTasks.post {
                    val nextPos = pos.coerceAtMost(tasks.size - 1)
                    rvTasks.findViewHolderForAdapterPosition(nextPos)?.itemView?.requestFocus()
                }
            }
        }
        Toast.makeText(this, "${task.label} dismissed", Toast.LENGTH_SHORT).show()
    }

    private fun takeScreenshot() {
        finish()
        Thread {
            Thread.sleep(300) // wait for drawer to close
            RootShell.cmd("input keyevent KEYCODE_SYSRQ")
        }.start()
    }

    private fun clearAllTasks() {
        val taskIds = tasks.map { it.taskId }
        Thread {
            for (id in taskIds) {
                RootShell.cmd("am stack remove $id")
            }
        }.start()
        tasks.clear()
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = View.VISIBLE
        rvTasks.visibility = View.GONE
        Toast.makeText(this, "All tasks cleared", Toast.LENGTH_SHORT).show()
        // Close after a beat
        rvTasks.postDelayed({ finish() }, 500)
    }

    // --- Adapter ---

    inner class TaskAdapter(
        private val items: List<RecentTask>,
        private val onOpen: (RecentTask) -> Unit,
        private val onDismiss: (RecentTask) -> Unit
    ) : RecyclerView.Adapter<TaskAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.ivIcon)
            val name: TextView = view.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task_card, parent, false)
            // Use physical DPI to size cards so they stay the same physical
            // size regardless of display density override
            val dm = parent.resources.displayMetrics
            val physicalDpi = DisplayMetrics.DENSITY_DEVICE_STABLE / 160f
            val scale = physicalDpi / dm.density
            val cardWidthPx = (160 * scale * dm.density).toInt()
            view.layoutParams.width = cardWidthPx
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val task = items[position]
            holder.name.text = task.label
            if (task.icon != null) {
                holder.icon.setImageDrawable(task.icon)
            } else {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // A button / click → open
            holder.itemView.setOnClickListener { onOpen(task) }
        }

        override fun getItemCount() = items.size
    }
}

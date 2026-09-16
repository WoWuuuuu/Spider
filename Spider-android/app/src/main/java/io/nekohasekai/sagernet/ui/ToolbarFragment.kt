package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        if (parentFragmentManager.backStackEntryCount > 0) {
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationOnClickListener {
                parentFragmentManager.popBackStack()
            }
        } else {
            /* 这里原来是个汉堡图标、点了开左侧抽屉。抽屉 2026-09-16 整个删掉了
               （`layout_main.xml` 里的 `DrawerLayout` + 两个 `NavigationView`），
               留着它就是一个「点了没有任何反应」的死键 —— 改成「回主页」的返回箭头。

               语义跟系统返回键完全一致（见 `MainActivity` 的 `onBackPressedDispatcher`：
               不在主页时回主页），只是把它在界面上显式摆出来。
               主页自己（`TopologyFragment`）会在自己的 setup 里把图标设成 null，
               所以不会出现「主页上挂着一个返回箭头」这种怪事。 */
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationOnClickListener {
                (activity as? MainActivity)?.navigateTo(MainActivity.DEST_HOME)
            }
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}

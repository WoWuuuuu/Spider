package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity

class ProfileSelectActivity : BaseNodeSelectActivity() {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val selected = intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)
        if (selected != null) {
            initialSelectedId = selected.id
        }
        super.onCreate(savedInstanceState)
    }

    override fun getTitleTextRes(): Int = R.string.select_profile

    override fun onProfileSelected(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

}
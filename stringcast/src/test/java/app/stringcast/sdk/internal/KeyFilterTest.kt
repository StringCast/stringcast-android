package app.stringcast.sdk.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFilterTest {

    private val filter = KeyFilter()

    @Test fun libraryPrefixesAreExcluded() {
        for (name in listOf(
            "abc_action_bar_home_description", "exo_controls_playback_speeds", "mtrl_picker_save",
            "material_slider_range_start", "m3_sys_motion", "m3c_bottom_sheet_pane_title", "bottomsheet_action_expand",
            "character_counter_pattern", "common_google_play_services_unknown_issue", "common_signin_button_text",
            "fcm_fallback_notification_channel_label", "androidx_startup", "call_notification_answer_action",
            "searchview_clear_text_content_description", "side_sheet_accessibility_pane_title",
            "path_password_eye", "item_view_role_tab", "fab_transformation_scrim_behavior",
        )) assertTrue(name, filter.isExcluded(name))
    }

    @Test fun completeLibraryNamesMatchExactlyOnly() {
        for (name in listOf(
            "tab", "selected", "not_selected", "expanded", "collapsed", "in_progress", "state_on", "range_start",
            "dropdown_menu", "default_popup_window_title", "search_menu_title", "common_open_on_phone",
            "password_toggle_content_description", "appbar_scrolling_view_behavior", "icon_content_description",
            "toggle_button_role", "tooltip_label",
        )) assertTrue(name, filter.isExcluded(name))
        // An app's own keys that merely start with a library name are kept.
        assertFalse(filter.isExcluded("tab_home"))
        assertFalse(filter.isExcluded("selected_count"))
        assertFalse(filter.isExcluded("expanded_title"))
        assertFalse(filter.isExcluded("bottom_sheet_behavior_x"))
        assertFalse(filter.isExcluded("table_header"))
        assertFalse(filter.isExcluded("tabs"))
        assertFalse(filter.isExcluded("selection_title"))
        assertFalse(filter.isExcluded("expandedness"))
        assertFalse(filter.isExcluded("range_starts"))
    }

    @Test fun generatedValuesAreExcludedExactly() {
        for (name in listOf(
            "google_app_id", "gcm_defaultSenderId", "default_web_client_id", "google_api_key",
            "google_crash_reporting_api_key", "google_storage_bucket", "project_id", "firebase_database_url",
            "com.google.firebase.crashlytics.mapping_file_id", "com.crashlytics.android.build_id",
            "facebook_app_id", "facebook_client_token", "fb_login_protocol_scheme",
        )) assertTrue(name, filter.isExcluded(name))
        assertFalse(filter.isExcluded("project_id_label"))
        assertFalse(filter.isExcluded("google_sign_in_title"))
    }

    @Test fun appStringsAreKept() {
        for (name in listOf("app_name", "welcome_title", "login_title", "items_count", "planets", "status_format")) {
            assertFalse(name, filter.isExcluded(name))
        }
        assertTrue(filter.isExcluded(""))
    }

    @Test fun appSpecificExclusions() {
        val f = KeyFilter(excludedKeys = setOf("debug_menu_title", " "), excludedKeyPrefixes = setOf("promo_", ""))
        assertTrue(f.isExcluded("debug_menu_title"))
        assertTrue(f.isExcluded("promo_banner"))
        assertTrue(f.isExcluded("exo_controls_playback_speeds")) // built-ins still apply
        assertFalse(f.isExcluded("debug_menu_title_2"))
        assertFalse(f.isExcluded("welcome_title")) // blank entries are ignored, not "match everything"
    }

    @Test fun reporterOnlyReportsOwnedNonExcludedKeys() {
        val owned = setOf("welcome_title", "promo_banner")
        val f = KeyFilter(excludedKeyPrefixes = setOf("promo_"))
        assertTrue(MissingKeyReporter.isReportable("welcome_title", owned, f))
        assertFalse(MissingKeyReporter.isReportable("promo_banner", owned, f)) // owned but excluded
        assertFalse(MissingKeyReporter.isReportable("exo_controls_playback_speeds", owned, f))
        assertFalse(MissingKeyReporter.isReportable("some_library_string", owned, f)) // not owned
        // No owned set computable: fall back to the denylist only.
        assertTrue(MissingKeyReporter.isReportable("some_library_string", null, f))
        assertFalse(MissingKeyReporter.isReportable("exo_controls_playback_speeds", null, f))
    }
}

package com.plate.launcher.onboarding

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import com.plate.launcher.R
import com.plate.launcher.utils.AvatarAutoCrop
import com.plate.launcher.utils.AvatarDisplay
import com.plate.launcher.utils.MultiUserManager
import com.plate.launcher.utils.SettingsHeaderController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step 3 — name, avatar, and cover photo. The avatar picker accepts a GIF
 * ("بروفايلك جيفت" — pick an animated GIF here and it plays everywhere the
 * app shows this user's avatar: onboarding itself, the settings header,
 * the Users list, and the user switcher popup; see
 * [MultiUserManager.setUserAvatarFromPickedMedia] for how the GIF-vs-still
 * decision is made from the picked file's actual bytes, and
 * [AvatarDisplay] for how every one of those surfaces shows it). Cover
 * photo stays a plain still image — GIF support here is specifically the
 * profile avatar, not the cover photo.
 *
 * Avatar is saved via MultiUserManager (the same storage the Users screen
 * and settings header already read from — not a separate onboarding-only
 * avatar), and cover photo via SettingsHeaderController.saveCoverPhoto
 * (same storage the settings header reads from) — so finishing this step
 * is immediately reflected everywhere those are shown, with no separate
 * onboarding-specific storage to keep in sync.
 */
class OnboardingProfileFragment : Fragment(R.layout.fragment_onboarding_profile) {

    private var pickingAvatar = true // which picker triggered the currently-in-flight gallery pick

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult

        if (pickingAvatar) {
            lifecycleScope.launch {
                val isGif = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?.let { MultiUserManager.isGif(it) } ?: false
                    } catch (e: Exception) { false }
                }
                val userId = MultiUserManager.getCurrentUserId()
                if (isGif) {
                    // Animated avatar — kept at full motion, no cropping applied.
                    MultiUserManager.setUserAvatarFromPickedMedia(ctx, userId, uri)
                } else {
                    // Fix for removed the manual crop screen — CropAvatarActivity:
                    // that screen turned out to be a bad experience in
                    // practice (a black-circle-with-no-visible-confirm-button
                    // bug, plus an extra tap most people never actually used
                    // for anything since a typical profile photo is already
                    // roughly centered). A still photo is now auto-cropped to
                    // a centered square and set immediately — see
                    // AvatarAutoCrop's kdoc for why this produces the same
                    // result the old screen did at its default (untouched)
                    // zoom/pan state.
                    val bmp = withContext(Dispatchers.IO) { AvatarAutoCrop.cropToSquare(ctx, uri) }
                    if (bmp == null) return@launch // picking effectively failed — nothing to show
                    MultiUserManager.setUserAvatarFromCroppedBitmap(ctx, userId, bmp)
                }
                val iv = view?.findViewById<ShapeableImageView>(R.id.onboarding_avatar_iv) ?: return@launch
                if (AvatarDisplay.showOn(iv, ctx, userId)) {
                    iv.visibility = View.VISIBLE
                    view?.findViewById<ImageView>(R.id.onboarding_avatar_icon)?.visibility = View.GONE
                }
            }
        } else {
            val bmp = try {
                ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null } ?: return@registerForActivityResult
            SettingsHeaderController.saveCoverPhoto(ctx, bmp)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        val nameEt = view.findViewById<TextInputEditText>(R.id.onboarding_name_et)
        nameEt.setText(com.plate.launcher.utils.DeviceUtils.getUserName(ctx))

        // Show whatever avatar already exists (e.g. the user goes back and revisits this
        // step) rather than always starting from the placeholder icon. AvatarDisplay
        // prefers the animated GIF over the static one automatically, so this shows
        // correctly whether the user set a GIF or a plain image on an earlier visit.
        val avatarIv = view.findViewById<ShapeableImageView>(R.id.onboarding_avatar_iv)
        if (AvatarDisplay.showOn(avatarIv, ctx, MultiUserManager.getCurrentUserId())) {
            avatarIv.visibility = View.VISIBLE
            view.findViewById<ImageView>(R.id.onboarding_avatar_icon).visibility = View.GONE
        }

        view.findViewById<View>(R.id.onboarding_avatar_wrapper).setOnClickListener {
            pickingAvatar = true
            pickImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"))
        }
        view.findViewById<MaterialButton>(R.id.onboarding_pick_cover_btn).setOnClickListener {
            pickingAvatar = false
            pickImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"))
        }
    }

    /**
     * Called by OnboardingActivity right before advancing past this step — persists the
     * typed display name. Kept as an explicit call (rather than saving on every
     * keystroke) so a half-typed name isn't written if the user backs out of onboarding
     * entirely partway through.
     */
    fun commitName() {
        val nameEt = view?.findViewById<TextInputEditText>(R.id.onboarding_name_et) ?: return
        val typed = nameEt.text?.toString()?.trim()
        if (!typed.isNullOrEmpty()) {
            com.plate.launcher.utils.OnboardingPrefs.setDisplayName(requireContext(), typed)
        }
    }
}

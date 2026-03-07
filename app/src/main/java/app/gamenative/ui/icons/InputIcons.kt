package app.gamenative.ui.icons

import androidx.annotation.DrawableRes
import app.gamenative.R

/**
 * Input prompt icons from Kenney's Input Prompts asset pack.
 * License: CC0 (Public Domain) - https://kenney.nl/assets/input-prompts
 *
 * Usage:
 * ```
 * Icon(
 *     painter = painterResource(InputIcons.Xbox.buttonA),
 *     contentDescription = "A button"
 * )
 * ```
 *
 * Or with Image:
 * ```
 * Image(
 *     painter = painterResource(InputIcons.Xbox.buttonA),
 *     contentDescription = "A button",
 *     modifier = Modifier.size(24.dp)
 * )
 * ```
 */
object InputIcons {

    /**
     * Xbox controller icons.
     * Includes face buttons, bumpers, triggers, D-pad, sticks, menu buttons, and controller variants.
     */
    object Xbox {
        // ==================== Face Buttons (Monochrome - Tintable) ====================
        @DrawableRes
        val buttonA = R.drawable.ic_input_xbox_button_a

        @DrawableRes
        val buttonB = R.drawable.ic_input_xbox_button_b

        @DrawableRes
        val buttonX = R.drawable.ic_input_xbox_button_x

        @DrawableRes
        val buttonY = R.drawable.ic_input_xbox_button_y

        // ==================== Face Buttons (Colored - with Xbox colors) ====================
        @DrawableRes
        val buttonColorA = R.drawable.ic_input_xbox_button_color_a

        @DrawableRes
        val buttonColorB = R.drawable.ic_input_xbox_button_color_b

        @DrawableRes
        val buttonColorX = R.drawable.ic_input_xbox_button_color_x

        @DrawableRes
        val buttonColorY = R.drawable.ic_input_xbox_button_color_y

        // ==================== Bumpers (Shoulder Buttons) ====================
        @DrawableRes
        val lb = R.drawable.ic_input_xbox_lb

        @DrawableRes
        val rb = R.drawable.ic_input_xbox_rb

        // ==================== Triggers ====================
        @DrawableRes
        val lt = R.drawable.ic_input_xbox_lt

        @DrawableRes
        val rt = R.drawable.ic_input_xbox_rt

        // ==================== Menu Buttons ====================

        /** ☰ Menu/Start button */
        @DrawableRes
        val menu = R.drawable.ic_input_xbox_button_menu
        val start = menu

        /** ⧉ View/Select button */
        @DrawableRes
        val view = R.drawable.ic_input_xbox_button_view
        val select = view

        @DrawableRes
        val share = R.drawable.ic_input_xbox_button_share

        @DrawableRes
        val guide = R.drawable.ic_input_xbox_guide

        /** Back button (older controllers) */
        @DrawableRes
        val back = R.drawable.ic_input_xbox_button_back

        @DrawableRes
        val backIcon = R.drawable.ic_input_xbox_button_back_icon

        /** Start button (text version) */
        @DrawableRes
        val startText = R.drawable.ic_input_xbox_button_start

        @DrawableRes
        val startIcon = R.drawable.ic_input_xbox_button_start_icon

        // ==================== D-Pad (Standard) ====================
        @DrawableRes
        val dpad = R.drawable.ic_input_xbox_dpad

        @DrawableRes
        val dpadUp = R.drawable.ic_input_xbox_dpad_up

        @DrawableRes
        val dpadDown = R.drawable.ic_input_xbox_dpad_down

        @DrawableRes
        val dpadLeft = R.drawable.ic_input_xbox_dpad_left

        @DrawableRes
        val dpadRight = R.drawable.ic_input_xbox_dpad_right

        @DrawableRes
        val dpadAll = R.drawable.ic_input_xbox_dpad_all

        @DrawableRes
        val dpadNone = R.drawable.ic_input_xbox_dpad_none

        @DrawableRes
        val dpadHorizontal = R.drawable.ic_input_xbox_dpad_horizontal

        @DrawableRes
        val dpadVertical = R.drawable.ic_input_xbox_dpad_vertical

        // ==================== D-Pad (Round Style) ====================
        @DrawableRes
        val dpadRound = R.drawable.ic_input_xbox_dpad_round

        @DrawableRes
        val dpadRoundUp = R.drawable.ic_input_xbox_dpad_round_up

        @DrawableRes
        val dpadRoundDown = R.drawable.ic_input_xbox_dpad_round_down

        @DrawableRes
        val dpadRoundLeft = R.drawable.ic_input_xbox_dpad_round_left

        @DrawableRes
        val dpadRoundRight = R.drawable.ic_input_xbox_dpad_round_right

        @DrawableRes
        val dpadRoundAll = R.drawable.ic_input_xbox_dpad_round_all

        @DrawableRes
        val dpadRoundHorizontal = R.drawable.ic_input_xbox_dpad_round_horizontal

        @DrawableRes
        val dpadRoundVertical = R.drawable.ic_input_xbox_dpad_round_vertical

        // ==================== Left Stick ====================
        @DrawableRes
        val stickL = R.drawable.ic_input_xbox_stick_l

        @DrawableRes
        val stickLPress = R.drawable.ic_input_xbox_stick_l_press

        @DrawableRes
        val stickLUp = R.drawable.ic_input_xbox_stick_l_up

        @DrawableRes
        val stickLDown = R.drawable.ic_input_xbox_stick_l_down

        @DrawableRes
        val stickLLeft = R.drawable.ic_input_xbox_stick_l_left

        @DrawableRes
        val stickLRight = R.drawable.ic_input_xbox_stick_l_right

        @DrawableRes
        val stickLHorizontal = R.drawable.ic_input_xbox_stick_l_horizontal

        @DrawableRes
        val stickLVertical = R.drawable.ic_input_xbox_stick_l_vertical

        // ==================== Right Stick ====================
        @DrawableRes
        val stickR = R.drawable.ic_input_xbox_stick_r

        @DrawableRes
        val stickRPress = R.drawable.ic_input_xbox_stick_r_press

        @DrawableRes
        val stickRUp = R.drawable.ic_input_xbox_stick_r_up

        @DrawableRes
        val stickRDown = R.drawable.ic_input_xbox_stick_r_down

        @DrawableRes
        val stickRLeft = R.drawable.ic_input_xbox_stick_r_left

        @DrawableRes
        val stickRRight = R.drawable.ic_input_xbox_stick_r_right

        @DrawableRes
        val stickRHorizontal = R.drawable.ic_input_xbox_stick_r_horizontal

        @DrawableRes
        val stickRVertical = R.drawable.ic_input_xbox_stick_r_vertical

        // ==================== Stick Text Labels ====================
        @DrawableRes
        val ls = R.drawable.ic_input_xbox_ls

        @DrawableRes
        val rs = R.drawable.ic_input_xbox_rs

        // ==================== Stick Variants ====================
        @DrawableRes
        val stickSideL = R.drawable.ic_input_xbox_stick_side_l

        @DrawableRes
        val stickSideR = R.drawable.ic_input_xbox_stick_side_r

        @DrawableRes
        val stickTopL = R.drawable.ic_input_xbox_stick_top_l

        @DrawableRes
        val stickTopR = R.drawable.ic_input_xbox_stick_top_r

        // ==================== Elite Paddles ====================
        @DrawableRes
        val elitePaddleTopLeft = R.drawable.ic_input_xbox_elite_paddle_top_left

        @DrawableRes
        val elitePaddleTopRight = R.drawable.ic_input_xbox_elite_paddle_top_right

        @DrawableRes
        val elitePaddleBottomLeft = R.drawable.ic_input_xbox_elite_paddle_bottom_left

        @DrawableRes
        val elitePaddleBottomRight = R.drawable.ic_input_xbox_elite_paddle_bottom_right

        // ==================== Controllers ====================
        @DrawableRes
        val controller = R.drawable.ic_input_xbox_xboxseries

        @DrawableRes
        val controllerXboxSeries = R.drawable.ic_input_xbox_controller_xboxseries

        @DrawableRes
        val controllerXboxOne = R.drawable.ic_input_xbox_controller_xboxone

        @DrawableRes
        val controllerXbox360 = R.drawable.ic_input_xbox_controller_xbox360

        @DrawableRes
        val controllerXboxAdaptive = R.drawable.ic_input_xbox_controller_xbox_adaptive
    }

    /**
     * Keyboard icons.
     * Includes all letter keys, number keys, function keys, arrow keys, modifiers, and special keys.
     */
    object Keyboard {
        // ==================== Letter Keys (A-Z) ====================
        @DrawableRes
        val a = R.drawable.ic_input_kbd_a

        @DrawableRes
        val b = R.drawable.ic_input_kbd_b

        @DrawableRes
        val c = R.drawable.ic_input_kbd_c

        @DrawableRes
        val d = R.drawable.ic_input_kbd_d

        @DrawableRes
        val e = R.drawable.ic_input_kbd_e

        @DrawableRes
        val f = R.drawable.ic_input_kbd_f

        @DrawableRes
        val g = R.drawable.ic_input_kbd_g

        @DrawableRes
        val h = R.drawable.ic_input_kbd_h

        @DrawableRes
        val i = R.drawable.ic_input_kbd_i

        @DrawableRes
        val j = R.drawable.ic_input_kbd_j

        @DrawableRes
        val k = R.drawable.ic_input_kbd_k

        @DrawableRes
        val l = R.drawable.ic_input_kbd_l

        @DrawableRes
        val m = R.drawable.ic_input_kbd_m

        @DrawableRes
        val n = R.drawable.ic_input_kbd_n

        @DrawableRes
        val o = R.drawable.ic_input_kbd_o

        @DrawableRes
        val p = R.drawable.ic_input_kbd_p

        @DrawableRes
        val q = R.drawable.ic_input_kbd_q

        @DrawableRes
        val r = R.drawable.ic_input_kbd_r

        @DrawableRes
        val s = R.drawable.ic_input_kbd_s

        @DrawableRes
        val t = R.drawable.ic_input_kbd_t

        @DrawableRes
        val u = R.drawable.ic_input_kbd_u

        @DrawableRes
        val v = R.drawable.ic_input_kbd_v

        @DrawableRes
        val w = R.drawable.ic_input_kbd_w

        @DrawableRes
        val x = R.drawable.ic_input_kbd_x

        @DrawableRes
        val y = R.drawable.ic_input_kbd_y

        @DrawableRes
        val z = R.drawable.ic_input_kbd_z

        // ==================== Number Keys (0-9) ====================
        @DrawableRes
        val key0 = R.drawable.ic_input_kbd_0

        @DrawableRes
        val key1 = R.drawable.ic_input_kbd_1

        @DrawableRes
        val key2 = R.drawable.ic_input_kbd_2

        @DrawableRes
        val key3 = R.drawable.ic_input_kbd_3

        @DrawableRes
        val key4 = R.drawable.ic_input_kbd_4

        @DrawableRes
        val key5 = R.drawable.ic_input_kbd_5

        @DrawableRes
        val key6 = R.drawable.ic_input_kbd_6

        @DrawableRes
        val key7 = R.drawable.ic_input_kbd_7

        @DrawableRes
        val key8 = R.drawable.ic_input_kbd_8

        @DrawableRes
        val key9 = R.drawable.ic_input_kbd_9

        // ==================== Function Keys (F1-F12) ====================
        @DrawableRes
        val f1 = R.drawable.ic_input_kbd_f1

        @DrawableRes
        val f2 = R.drawable.ic_input_kbd_f2

        @DrawableRes
        val f3 = R.drawable.ic_input_kbd_f3

        @DrawableRes
        val f4 = R.drawable.ic_input_kbd_f4

        @DrawableRes
        val f5 = R.drawable.ic_input_kbd_f5

        @DrawableRes
        val f6 = R.drawable.ic_input_kbd_f6

        @DrawableRes
        val f7 = R.drawable.ic_input_kbd_f7

        @DrawableRes
        val f8 = R.drawable.ic_input_kbd_f8

        @DrawableRes
        val f9 = R.drawable.ic_input_kbd_f9

        @DrawableRes
        val f10 = R.drawable.ic_input_kbd_f10

        @DrawableRes
        val f11 = R.drawable.ic_input_kbd_f11

        @DrawableRes
        val f12 = R.drawable.ic_input_kbd_f12

        // ==================== Arrow Keys ====================
        @DrawableRes
        val arrowUp = R.drawable.ic_input_kbd_arrow_up

        @DrawableRes
        val arrowDown = R.drawable.ic_input_kbd_arrow_down

        @DrawableRes
        val arrowLeft = R.drawable.ic_input_kbd_arrow_left

        @DrawableRes
        val arrowRight = R.drawable.ic_input_kbd_arrow_right

        // ==================== Arrow Key Cluster ====================
        @DrawableRes
        val arrows = R.drawable.ic_input_kbd_arrows

        @DrawableRes
        val arrowsAll = R.drawable.ic_input_kbd_arrows_all

        @DrawableRes
        val arrowsNone = R.drawable.ic_input_kbd_arrows_none

        @DrawableRes
        val arrowsUp = R.drawable.ic_input_kbd_arrows_up

        @DrawableRes
        val arrowsDown = R.drawable.ic_input_kbd_arrows_down

        @DrawableRes
        val arrowsLeft = R.drawable.ic_input_kbd_arrows_left

        @DrawableRes
        val arrowsRight = R.drawable.ic_input_kbd_arrows_right

        @DrawableRes
        val arrowsHorizontal = R.drawable.ic_input_kbd_arrows_horizontal

        @DrawableRes
        val arrowsVertical = R.drawable.ic_input_kbd_arrows_vertical

        // ==================== Modifier Keys ====================
        @DrawableRes
        val shift = R.drawable.ic_input_kbd_shift

        @DrawableRes
        val shiftIcon = R.drawable.ic_input_kbd_shift_icon

        @DrawableRes
        val ctrl = R.drawable.ic_input_kbd_ctrl

        @DrawableRes
        val alt = R.drawable.ic_input_kbd_alt

        @DrawableRes
        val command = R.drawable.ic_input_kbd_command

        @DrawableRes
        val option = R.drawable.ic_input_kbd_option

        @DrawableRes
        val win = R.drawable.ic_input_kbd_win

        @DrawableRes
        val function = R.drawable.ic_input_kbd_function

        // ==================== Special Keys ====================
        @DrawableRes
        val space = R.drawable.ic_input_kbd_space

        @DrawableRes
        val spaceIcon = R.drawable.ic_input_kbd_space_icon

        @DrawableRes
        val tab = R.drawable.ic_input_kbd_tab

        @DrawableRes
        val tabIcon = R.drawable.ic_input_kbd_tab_icon

        @DrawableRes
        val tabIconAlt = R.drawable.ic_input_kbd_tab_icon_alternative

        @DrawableRes
        val enter = R.drawable.ic_input_kbd_enter

        @DrawableRes
        val returnKey = R.drawable.ic_input_kbd_return

        @DrawableRes
        val escape = R.drawable.ic_input_kbd_escape

        @DrawableRes
        val backspace = R.drawable.ic_input_kbd_backspace

        @DrawableRes
        val backspaceIcon = R.drawable.ic_input_kbd_backspace_icon

        @DrawableRes
        val backspaceIconAlt = R.drawable.ic_input_kbd_backspace_icon_alternative

        @DrawableRes
        val delete = R.drawable.ic_input_kbd_delete

        @DrawableRes
        val insert = R.drawable.ic_input_kbd_insert

        @DrawableRes
        val home = R.drawable.ic_input_kbd_home

        @DrawableRes
        val end = R.drawable.ic_input_kbd_end

        @DrawableRes
        val pageUp = R.drawable.ic_input_kbd_page_up

        @DrawableRes
        val pageDown = R.drawable.ic_input_kbd_page_down

        @DrawableRes
        val capslock = R.drawable.ic_input_kbd_capslock

        @DrawableRes
        val capslockIcon = R.drawable.ic_input_kbd_capslock_icon

        @DrawableRes
        val numlock = R.drawable.ic_input_kbd_numlock

        @DrawableRes
        val printscreen = R.drawable.ic_input_kbd_printscreen

        // ==================== Numpad Keys ====================
        @DrawableRes
        val numpadEnter = R.drawable.ic_input_kbd_numpad_enter

        @DrawableRes
        val numpadPlus = R.drawable.ic_input_kbd_numpad_plus

        // ==================== Punctuation & Symbols ====================
        @DrawableRes
        val apostrophe = R.drawable.ic_input_kbd_apostrophe

        @DrawableRes
        val quote = R.drawable.ic_input_kbd_quote

        @DrawableRes
        val bracketOpen = R.drawable.ic_input_kbd_bracket_open

        @DrawableRes
        val bracketClose = R.drawable.ic_input_kbd_bracket_close

        @DrawableRes
        val bracketLess = R.drawable.ic_input_kbd_bracket_less

        @DrawableRes
        val bracketGreater = R.drawable.ic_input_kbd_bracket_greater

        @DrawableRes
        val colon = R.drawable.ic_input_kbd_colon

        @DrawableRes
        val semicolon = R.drawable.ic_input_kbd_semicolon

        @DrawableRes
        val comma = R.drawable.ic_input_kbd_comma

        @DrawableRes
        val period = R.drawable.ic_input_kbd_period

        @DrawableRes
        val question = R.drawable.ic_input_kbd_question

        @DrawableRes
        val exclamation = R.drawable.ic_input_kbd_exclamation

        @DrawableRes
        val asterisk = R.drawable.ic_input_kbd_asterisk

        @DrawableRes
        val caret = R.drawable.ic_input_kbd_caret

        @DrawableRes
        val tilde = R.drawable.ic_input_kbd_tilde

        @DrawableRes
        val minus = R.drawable.ic_input_kbd_minus

        @DrawableRes
        val plus = R.drawable.ic_input_kbd_plus

        @DrawableRes
        val equals = R.drawable.ic_input_kbd_equals

        @DrawableRes
        val slashForward = R.drawable.ic_input_kbd_slash_forward

        @DrawableRes
        val slashBack = R.drawable.ic_input_kbd_slash_back

        // ==================== Other ====================
        @DrawableRes
        val keyboard = R.drawable.ic_input_kbd_keyboard

        @DrawableRes
        val any = R.drawable.ic_input_kbd_any

        @DrawableRes
        val outline = R.drawable.ic_input_kbd_outline
    }

    /**
     * Mouse icons.
     * Includes mouse device, buttons, and scroll wheel.
     */
    object Mouse {
        // ==================== Device ====================
        @DrawableRes
        val device = R.drawable.ic_input_kbd_mouse

        @DrawableRes
        val deviceSmall = R.drawable.ic_input_kbd_mouse_small

        // ==================== Buttons ====================
        @DrawableRes
        val left = R.drawable.ic_input_kbd_mouse_left

        @DrawableRes
        val right = R.drawable.ic_input_kbd_mouse_right

        // ==================== Scroll Wheel ====================
        @DrawableRes
        val scroll = R.drawable.ic_input_kbd_mouse_scroll

        @DrawableRes
        val scrollUp = R.drawable.ic_input_kbd_mouse_scroll_up

        @DrawableRes
        val scrollDown = R.drawable.ic_input_kbd_mouse_scroll_down

        @DrawableRes
        val scrollVertical = R.drawable.ic_input_kbd_mouse_scroll_vertical

        // ==================== Movement ====================
        @DrawableRes
        val move = R.drawable.ic_input_kbd_mouse_move

        @DrawableRes
        val horizontal = R.drawable.ic_input_kbd_mouse_horizontal

        @DrawableRes
        val vertical = R.drawable.ic_input_kbd_mouse_vertical
    }

    /**
     * Touch gesture icons.
     * Includes taps, swipes, pinch-to-zoom, and rotation gestures.
     */
    object Touch {
        // ==================== Fingers ====================
        @DrawableRes
        val fingerOne = R.drawable.ic_input_touch_finger_one

        @DrawableRes
        val fingerTwo = R.drawable.ic_input_touch_finger_two

        // ==================== Hands ====================
        @DrawableRes
        val handOpen = R.drawable.ic_input_touch_hand_open

        @DrawableRes
        val handClosed = R.drawable.ic_input_touch_hand_closed

        // ==================== Single Tap ====================
        @DrawableRes
        val tap = R.drawable.ic_input_touch_tap

        @DrawableRes
        val tapDouble = R.drawable.ic_input_touch_tap_double

        @DrawableRes
        val tapHold = R.drawable.ic_input_touch_tap_hold

        // ==================== Two Finger Tap ====================
        @DrawableRes
        val twoFingers = R.drawable.ic_input_touch_two

        @DrawableRes
        val twoFingersDouble = R.drawable.ic_input_touch_two_double

        @DrawableRes
        val twoFingersHold = R.drawable.ic_input_touch_two_hold

        // ==================== Single Finger Swipe ====================
        @DrawableRes
        val swipeUp = R.drawable.ic_input_touch_swipe_up

        @DrawableRes
        val swipeDown = R.drawable.ic_input_touch_swipe_down

        @DrawableRes
        val swipeLeft = R.drawable.ic_input_touch_swipe_left

        @DrawableRes
        val swipeRight = R.drawable.ic_input_touch_swipe_right

        @DrawableRes
        val swipeMove = R.drawable.ic_input_touch_swipe_move

        @DrawableRes
        val swipeHorizontal = R.drawable.ic_input_touch_swipe_horizontal

        @DrawableRes
        val swipeVertical = R.drawable.ic_input_touch_swipe_vertical

        // ==================== Two Finger Swipe ====================
        @DrawableRes
        val swipeTwoUp = R.drawable.ic_input_touch_swipe_two_up

        @DrawableRes
        val swipeTwoDown = R.drawable.ic_input_touch_swipe_two_down

        @DrawableRes
        val swipeTwoLeft = R.drawable.ic_input_touch_swipe_two_left

        @DrawableRes
        val swipeTwoRight = R.drawable.ic_input_touch_swipe_two_right

        @DrawableRes
        val swipeTwoMove = R.drawable.ic_input_touch_swipe_two_move

        @DrawableRes
        val swipeTwoHorizontal = R.drawable.ic_input_touch_swipe_two_horizontal

        @DrawableRes
        val swipeTwoVertical = R.drawable.ic_input_touch_swipe_two_vertical

        // ==================== Rotation ====================
        @DrawableRes
        val rotateLeft = R.drawable.ic_input_touch_rotate_left

        @DrawableRes
        val rotateRight = R.drawable.ic_input_touch_rotate_right

        // ==================== Zoom (Pinch) ====================
        @DrawableRes
        val zoomIn = R.drawable.ic_input_touch_zoom_in

        @DrawableRes
        val zoomOut = R.drawable.ic_input_touch_zoom_out
    }
}

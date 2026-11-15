package app.gamenative.theme

/** The example theme [ThemeManager.exportStarterTheme] writes; it doubles as the `--gn-*` authoring reference. */
object StarterTheme {

    const val FOLDER = "GameNative Starter"
    const val IMAGE = "background.png"

    val themeJson: String = """
{
  "name": "GameNative Starter",
  "author": "You",
  "version": "v1.0",
  "description": "Example theme for GameNative. Edit theme.css, zip the folder and import it.",
  "manifest_version": 9,
  "target": "System-Wide",
  "inject": {
    "theme.css": ["All"]
  },
  "patches": {
    "Background": {
      "type": "dropdown",
      "default": "Gradient",
      "values": {
        "Gradient": {
          "--gn-background-gradient": ["linear-gradient(160deg, #1f2a44 0%, #0b0f17 100%)", "All"]
        },
        "Image": {},
        "Plain": {
          "--gn-background": ["#10141c", "All"]
        }
      },
      "components": [
        {
          "name": "Background image",
          "type": "image-picker",
          "on": "Image",
          "default": "$FOLDER/$IMAGE",
          "css_variable": "gn-background-image",
          "tabs": ["All"]
        }
      ]
    },
    "Accent": {
      "type": "none",
      "default": "Custom",
      "values": {
        "Custom": {}
      },
      "components": [
        {
          "name": "Accent color",
          "type": "color-picker",
          "on": "Custom",
          "default": "#4fc3f7",
          "css_variable": "gn-primary",
          "tabs": ["All"]
        }
      ]
    }
  }
}
""".trimStart()

    val themeCss: String = """
/*
 * GameNative theme variables. Set any of them in :root; anything left out keeps the app default.
 * A --gn-* variable always wins over values GameNative reads from Steam class rules.
 * Lengths are in px (1px = 1dp). url() paths are relative to this file.
 *
 *   --gn-background          page background color
 *   --gn-surface             panels, dialogs, side menus
 *   --gn-surface-variant     tabs and settings rows
 *   --gn-primary             accent: buttons, toggles, progress bars
 *   --gn-on-primary          text on the accent color
 *   --gn-text                main text
 *   --gn-text-muted          secondary text
 *   --gn-outline             borders and dividers
 *   --gn-focus               controller focus highlight
 *   --gn-bar                 top and bottom bars; alpha allowed, e.g. rgba(0, 0, 0, 0.5)
 *   --gn-corner-radius       corner radius, e.g. 12px
 *   --gn-font                url() of a .ttf, .otf, .woff or .woff2 file inside the theme folder, or an https:// one
 *                            (downloaded once when the theme is installed)
 *   --gn-background-image    url() of an image inside the theme folder
 *   --gn-background-gradient linear-gradient(...), used when there is no background image
 *   --gn-panel-opacity       0..1 opacity of panels, e.g. 0.85
 *   --gn-blur                blur behind panels, e.g. 12px
 *
 * Per component:
 *   --gn-card                library tiles and list rows
 *   --gn-button              secondary buttons
 *   --gn-on-button           text on secondary buttons
 *   --gn-toggle              switches, sliders and progress bars when on
 *   --gn-menu                context menus, dropdowns and dialogs
 *   --gn-on-menu             text in menus
 *   --gn-accent-gradient     linear-gradient(...) fill for accent buttons, toggles and progress bars
 *   --gn-focus-glow          glow around the focused item in the focus color, e.g. 8px
 *   --gn-heading             section headings
 *   --gn-subtitle            line under game titles
 *   --gn-text-glow           glow behind text, e.g. 6px #ff00aa
 *   --gn-header              top bar color, alpha allowed; --gn-footer the same for the button-hint bar
 *   --gn-header-fill         gradients over the bar color; also --gn-footer-fill
 *   --gn-header-border       line under the top bar, e.g. 2px #ff00aa; --gn-footer-border the line above the footer
 *   --gn-header-glow         glow around the bar, e.g. 24px #ff00aa; also --gn-footer-glow
 *   --gn-header-blur         blur behind the bar, e.g. 5px; also --gn-footer-blur
 *   --gn-header-text         text and icons on the bar; also --gn-footer-text
 *   --gn-header-height       bar height, 0.25..2 (1 = default); also --gn-footer-height
 *   --gn-footer-glyph-invert 1 draws the button glyphs on the footer dark, for a light footer
 *   --gn-toggle-on           switch track when on, color or gradient
 *   --gn-toggle-off          switch track when off
 *   --gn-thumb-on, --gn-thumb-off   switch handle colors
 *   --gn-slider-track, --gn-slider-thumb   slider track and handle
 *   --gn-button-fill         secondary button fill, color or gradient
 *   --gn-button-focus        secondary button fill while focused; --gn-on-button-focus its text
 *   --gn-button-underline    line under secondary buttons, e.g. 2px #ffffff
 *   --gn-tab                 fill of the selected tab; --gn-on-tab its text
 *   --gn-focus-ring          focus ring width, e.g. 2px (0 = none); --gn-focus-ring-color its color
 *   --gn-focus-fill          fill of the focused row; --gn-on-focus-fill its text
 *   --gn-focus-underline     gradient or color under the focused item; --gn-focus-underline-height e.g. 4px
 *   --gn-focus-blink         1 makes the focus ring pulse
 *   --gn-menu-radius         corner radius of menus and dialogs, e.g. 24px
 *   --gn-menu-fill           gradients over the menu color
 *   --gn-menu-opacity        0..1
 *   --gn-menu-blur           blur behind menus, e.g. 20px
 *   --gn-menu-border         e.g. 2px #00ffff; --gn-menu-glow e.g. 10px
 *   --gn-menu-focus          fill of the focused menu item; --gn-on-menu-focus its text
 *   --gn-panel-fill          side panels: gradients (linear or radial) over a color
 *
 * Text:
 *   --gn-text-size           size of all text, 0.8..1.3 (1 = default)
 *   --gn-title-size          game titles on top of --gn-text-size, e.g. 1.2; --gn-footer-text-size the button-hint labels
 *   --gn-heading-weight      section heading weight, 100..900 or bold; --gn-selected-weight the selected tab or menu item
 *   --gn-letter-spacing      extra space between letters, e.g. 0.05em
 *   --gn-heading-case        uppercase or none; also --gn-tab-case (tab labels) and --gn-footer-case (button hints)
 *   --gn-text-case           uppercase upper-cases all labels, headings, tabs, button hints and game titles
 *
 * Game artwork behind the home carousel (fade and overlay also on the game page):
 *   --gn-hero-opacity        0..1
 *   --gn-hero-blur           e.g. 6px (0 = sharp)
 *   --gn-hero-saturation     0..1 (1 = full color)
 *   --gn-hero-overlay        darkening, 0..1.5 (0 = none, 1 = default)
 *   --gn-hero-fade           share of the height at the bottom that fades out, e.g. 0.1
 *
 * Animation (seconds for one pass of a gradient sliding back and forth; 0 = still):
 *   --gn-fill-slide          accent fills: switches, sliders, progress bars, accent buttons, e.g. 12s
 *   --gn-panel-slide         side panel gradients
 *   --gn-focus-underline-slide  the line under the focused item
 *
 * Layout:
 *   --gn-card-scale          library tile size, 0.5..1.5 (1 = default)
 *   --gn-library-layout      grid, list, carousel or row (a shelf of covers under the game artwork)
 *   --gn-title-align         start or center, for game titles
 *   --gn-title-stroke        outline around game titles, e.g. 2px #000
 *   --gn-hide-badges         1 hides compatibility and status badges on tiles (1/0, true/false)
 *   --gn-hide-footer-hints   1 hides the controller button hints at the bottom
 *   --gn-hero-scale          game page artwork height, 0.5..2 (1 = default)
 *   --gn-card-tilt           tile tilt, -15deg..15deg
 *   --gn-card-corner-radius  corner radius of library tiles only, e.g. 4px
 *   --gn-icon-corner-radius  corner radius of small game icons, e.g. 0px
 *   --gn-title-stroke        also takes a blur, e.g. 1px #000 2px for a soft outline
 *   --gn-subtitle-stroke     outline of the line under game titles, as --gn-title-stroke
 *   --gn-shelf-wide-first    0 gives the shelf's first tile the same shape as the others (1 = wide, the default)
 *   --gn-shelf-row-anchor    where the shelf's covers sit between the top tab bar and the hint bar: 0 hangs them from
 *                            the top (the default, as on Steam's home), 1 sets them on the bottom, 0.5 centers them
 *   --gn-shelf-row-offset    how far the covers sit below that place, in Steam px (1px = screen height / 533.33, the
 *                            Steam Deck's), e.g. -40px; default 58px, where Steam's home puts them under its header
 *   --gn-shelf-title-offset  the shelf's game title against the covers, in Steam px: negative puts its bottom that far
 *                            above the covers, otherwise its top that far under them; default 10px
 *   --gn-shelf-hide-title    1 hides the shelf's game title and the line under it
 *   --gn-shelf-hide-subtitle 1 hides only the line under the shelf's game title (status and play time)
 *   --gn-card-tilt-after     tilt of the tiles after the focused one (default: --gn-card-tilt)
 *   --gn-card-turn           3D turn of the tiles about their vertical axis, e.g. -10deg
 *   --gn-card-turn-after     3D turn of the tiles after the focused one (default: --gn-card-turn)
 *   --gn-card-turn-perspective  viewer distance of the turn, e.g. 600px
 *   --gn-card-focused-flat   1 draws the focused tile without the tilt or turn
 *
 * Covers:
 *   --gn-card-rest-brightness   brightness of the covers that are not focused, 0..2 (1 = default)
 *   --gn-card-rest-saturation   color of the covers that are not focused, 0..2 (0 = gray, 1 = default)
 *   --gn-card-focused-scale     extra size of the focused cover, e.g. 1.1
 *   --gn-card-focus-pop         size of the focused cover in place of Steam's lift, e.g. 1 (no lift); Steam's is about 1.05
 *   --gn-card-focus-pop-wide    the same for the wide cover; Steam's is about 1.02
 *   --gn-tile-frame             url() of an image drawn around each cover; --gn-tile-frame-mask a mask image for the cover
 *   --gn-tile-frame-inset-left, --gn-tile-frame-inset-top, --gn-tile-frame-inset-width, --gn-tile-frame-inset-height
 *                               where the cover sits in the frame, as fractions of the frame (default 0, 0, 1, 1)
 *   --gn-tile-frame-wide        the same for the wide cover: --gn-tile-frame-wide-mask, --gn-tile-frame-wide-inset-left,
 *                               --gn-tile-frame-wide-inset-top, --gn-tile-frame-wide-inset-width, --gn-tile-frame-wide-inset-height
 *   --gn-cover-reflection       opacity of a mirror image under each cover, 0..1
 *   --gn-cover-reflection-focused  its opacity under the focused cover
 *   --gn-cover-reflection-fade  share of the mirrored cover shown before it fades out, 0..1 (default 1)
 *   --gn-cover-reflection-blur  e.g. 2px; --gn-cover-reflection-gap the space between cover and reflection, e.g. 4px
 *
 * Home artwork:
 *   --gn-hero-image          url() of an image shown in place of the focused game's artwork
 *   --gn-hero-image-tile     1 repeats the image instead of covering the screen with it
 *   --gn-hero-image-scale    image width as a share of the screen, e.g. 1.5
 *   --gn-hero-height         the artwork only over this top share of the screen, e.g. 0.5
 *
 * Library grid:
 *   --gn-grid-columns        number of columns, 1..12
 *   --gn-grid-tile-aspect    cover width / height, e.g. 1 for square covers
 *   --gn-uninstalled-saturation  color of covers of games that are not installed, 0..1 (0 = gray)
 *   --gn-uninstalled-opacity     their opacity, 0..1
 *   --gn-hide-tab-counts     1 hides the game counts next to the library tab names
 *
 * Colors: #rgb, #rrggbb, #rrggbbaa, rgb(), rgba(), hsl(), hsla() or a CSS color name.
 * var(--name, fallback) works, so one color can feed several variables.
 * The Accent color and Background options in theme.json set --gn-primary and the background.
 */
:root {
  --gn-surface: #182030;
  --gn-surface-variant: #222c40;
  --gn-on-primary: #0b0f17;
  --gn-text: #e8edf5;
  --gn-text-muted: #9aa6b8;
  --gn-outline: #2e3a52;
  --gn-focus: var(--gn-primary, #4fc3f7);
  --gn-bar: rgba(11, 15, 23, 0.8);
  --gn-corner-radius: 12px;
  --gn-panel-opacity: 0.92;
  /* --gn-font: url(fonts/MyFont.ttf); */
  /* --gn-blur: 12px; */
  /* --gn-card: #222c40; */
  /* --gn-menu: #182030; */
  /* --gn-focus-glow: 8px; */
  /* --gn-card-scale: 1.2; */
  /* --gn-title-align: center; */
}
""".trimStart()
}

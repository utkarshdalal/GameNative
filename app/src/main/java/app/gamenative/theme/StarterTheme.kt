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
          "name": "Accent colour",
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
 *   --gn-background          page background colour
 *   --gn-surface             panels, dialogs, side menus
 *   --gn-surface-variant     tabs and settings rows
 *   --gn-primary             accent: buttons, toggles, progress bars
 *   --gn-on-primary          text on the accent colour
 *   --gn-text                main text
 *   --gn-text-muted          secondary text
 *   --gn-outline             borders and dividers
 *   --gn-focus               controller focus highlight
 *   --gn-bar                 top and bottom bars; alpha allowed, e.g. rgba(0, 0, 0, 0.5)
 *   --gn-corner-radius       corner radius, e.g. 12px
 *   --gn-font                url() of a .ttf or .otf file inside the theme folder
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
 *   --gn-focus-glow          glow around the focused item in the focus colour, e.g. 8px
 *
 * Layout:
 *   --gn-card-scale          library tile size, 0.5..1.5 (1 = default)
 *   --gn-library-layout      grid, list or carousel
 *   --gn-title-align         start or center, for game titles
 *   --gn-title-stroke        outline around game titles, e.g. 2px #000
 *   --gn-hide-badges         1 hides compatibility and status badges on tiles (1/0, true/false)
 *   --gn-hide-footer-hints   1 hides the controller button hints at the bottom
 *   --gn-hero-scale          game page artwork height, 0.5..2 (1 = default)
 *   --gn-card-tilt           tile tilt, -15deg..15deg
 *   --gn-card-corner-radius  corner radius of library tiles only, e.g. 4px
 *
 * Colours: #rgb, #rrggbb, #rrggbbaa, rgb(), rgba(), hsl(), hsla() or a CSS colour name.
 * var(--name, fallback) works, so one colour can feed several variables.
 * The Accent colour and Background options in theme.json set --gn-primary and the background.
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

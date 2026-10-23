package com.octaviantocan.pulls.ui.review

import androidx.annotation.DrawableRes
import com.octaviantocan.pulls.R

/** Compatibility for existing Android-only callers while the shared file-name policy owns selection. */
@DrawableRes
fun fileIconRes(path: String): Int = when (fileIconName(path)) {
    "ic_cat_npm" -> R.drawable.ic_cat_npm
    "ic_cat_vue" -> R.drawable.ic_cat_vue
    "ic_cat_typescript_react" -> R.drawable.ic_cat_typescript_react
    "ic_cat_javascript_react" -> R.drawable.ic_cat_javascript_react
    "ic_cat_typescript_def" -> R.drawable.ic_cat_typescript_def
    "ic_cat_typescript" -> R.drawable.ic_cat_typescript
    "ic_cat_javascript" -> R.drawable.ic_cat_javascript
    "ic_cat_json" -> R.drawable.ic_cat_json
    "ic_cat_yaml" -> R.drawable.ic_cat_yaml
    "ic_cat_toml" -> R.drawable.ic_cat_toml
    "ic_cat_markdown" -> R.drawable.ic_cat_markdown
    "ic_cat_html" -> R.drawable.ic_cat_html
    "ic_cat_css" -> R.drawable.ic_cat_css
    "ic_cat_sass" -> R.drawable.ic_cat_sass
    "ic_cat_svg" -> R.drawable.ic_cat_svg
    "ic_cat_image" -> R.drawable.ic_cat_image
    "ic_cat_font" -> R.drawable.ic_cat_font
    else -> R.drawable.ic_cat_file
}

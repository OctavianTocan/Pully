package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.core.LinkPlace

/**
 * A place on a pull request a link asked for ([LinkPlace]), gone to once: Back to the page later finds it where the reader
 * left it, not on the place again.
 */
class PlaceRequest(val place: LinkPlace) {
    var done = false
}

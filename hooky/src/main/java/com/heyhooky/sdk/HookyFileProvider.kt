package com.heyhooky.sdk

import androidx.core.content.FileProvider

// Un FileProvider à nous : déclaré sous son propre nom, il ne se dispute pas
// la place avec celui que l'app déclare peut-être déjà.
internal class HookyFileProvider : FileProvider()

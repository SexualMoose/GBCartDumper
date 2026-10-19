package com.tyler.gbcartdumper

import android.app.Application
import com.tyler.gbcartdumper.fleet.FleetReporter

/** Application class: installs FleetReporter (crash, freeze and error reports to FleetGuardian). */
class GBCartDumperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FleetReporter.install(this)
    }
}

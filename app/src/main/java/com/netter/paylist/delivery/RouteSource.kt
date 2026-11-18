package com.netter.paylist.delivery

import com.netter.paylist.Route
import com.netter.paylist.Store

/**
 * The delivery worker's view of routes: a seam between the worker
 * (pure logic, unit-tested with fakes) and [Store] (Android
 * `SharedPreferences`, untestable on the JVM).
 */
interface RouteSource {
    fun routes(): List<Route>
    fun routeById(id: String): Route?
    fun deviceLabel(): String
}

/** Production [RouteSource] backed by [Store]. */
class StoreRouteSource(private val store: Store) : RouteSource {
    override fun routes(): List<Route> = store.routes()
    override fun routeById(id: String): Route? = store.routeById(id)
    override fun deviceLabel(): String = store.deviceLabel()
}

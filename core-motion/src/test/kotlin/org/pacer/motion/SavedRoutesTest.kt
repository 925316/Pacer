package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedRoutesTest {

    private val start = GeoPoint(43.8256, 87.6168)
    private val other = GeoPoint(43.8266, 87.6168)

    private fun draft(): RouteDraft = RouteDraft(listOf(start, other))

    @Test
    fun `saving a draft creates an entry with a stable id`() {
        val routes = SavedRoutes()
        val result = routes.save(draft(), "Morning loop")
        assertTrue(result is SavedRoutes.SaveResult.Saved)
        val saved = (result as SavedRoutes.SaveResult.Saved).route
        assertEquals("Morning loop", saved.name)
        assertEquals(2, saved.points.size)
        assertEquals(1, routes.size)
        assertFalse(result.replaced)
        // Two points ~111 m apart on the sphere GeoMath measures.
        assertTrue("length was ${saved.lengthMeters}", saved.lengthMeters in 100.0..120.0)
    }

    @Test
    fun `names are trimmed and an empty one is refused`() {
        val routes = SavedRoutes()
        val blank = routes.save(draft(), "   ")
        assertTrue(blank is SavedRoutes.SaveResult.InvalidName)
        assertEquals(0, routes.size)

        val padded = routes.save(draft(), "  Park  ")
        assertEquals("Park", (padded as SavedRoutes.SaveResult.Saved).route.name)
    }

    @Test
    fun `an overlong name is refused rather than truncated`() {
        // Truncating would silently save something the user did not type.
        val routes = SavedRoutes()
        val long = "x".repeat(SavedRoutes.MAX_NAME_LENGTH + 1)
        assertTrue(routes.save(draft(), long) is SavedRoutes.SaveResult.InvalidName)
    }

    @Test
    fun `a draft that is not a route cannot be saved`() {
        val routes = SavedRoutes()
        val invalid = routes.save(RouteDraft(listOf(start)), "One point")
        assertTrue(invalid is SavedRoutes.SaveResult.InvalidRoute)
    }

    @Test
    fun `saving over an id replaces instead of duplicating`() {
        val routes = SavedRoutes()
        val first = (routes.save(draft(), "Loop") as SavedRoutes.SaveResult.Saved).route
        val bigger = RouteDraft(listOf(start, other, GeoPoint(43.8276, 87.6168)))
        val second = routes.save(bigger, "Loop longer", replaceId = first.id) as SavedRoutes.SaveResult.Saved
        assertTrue(second.replaced)
        assertEquals(1, routes.size)
        assertEquals(first.id, second.route.id)
        assertEquals(3, second.route.points.size)
    }

    @Test
    fun `renaming keeps the id and refuses a blank name`() {
        val routes = SavedRoutes()
        val saved = (routes.save(draft(), "Old") as SavedRoutes.SaveResult.Saved).route
        assertTrue(routes.rename(saved.id, "New"))
        assertEquals("New", routes.find(saved.id)?.name)
        assertFalse(routes.rename(saved.id, " "))
        // A rename to an unusable name leaves the old one alone.
        assertEquals("New", routes.find(saved.id)?.name)
        assertFalse(routes.rename(999L, "Whatever"))
    }

    @Test
    fun `removing an entry and loading it back`() {
        val routes = SavedRoutes()
        val a = (routes.save(draft(), "A") as SavedRoutes.SaveResult.Saved).route
        val b = (routes.save(draft(), "B") as SavedRoutes.SaveResult.Saved).route
        assertTrue(routes.remove(a.id))
        assertNull(routes.find(a.id))
        assertNotNull(routes.draftOf(b.id))
        assertFalse(routes.remove(a.id))
    }

    @Test
    fun `a removed id is not reused`() {
        // Reusing an id would make a stale "loaded route" reference name a different
        // route after a delete and a save.
        val routes = SavedRoutes()
        val a = (routes.save(draft(), "A") as SavedRoutes.SaveResult.Saved).route
        routes.remove(a.id)
        val b = (routes.save(draft(), "B") as SavedRoutes.SaveResult.Saved).route
        assertTrue("new id ${b.id} reused ${a.id}", b.id != a.id)
    }

    @Test
    fun `the list survives a round trip through its own format`() {
        val routes = SavedRoutes()
        routes.save(draft(), "One")
        routes.save(draft(), "Two \"quoted\" \\ slash")
        val json = routes.toJson()
        val restored = SavedRoutes.parse(json)
        assertEquals(2, restored.size)
        assertEquals(listOf("One", "Two \"quoted\" \\ slash"), restored.routes.map { it.name })
        assertEquals(2, restored.routes.first().points.size)
        // Names with quotes and backslashes must not corrupt the entries after them.
        assertEquals(restored.routes.map { it.id }, routes.routes.map { it.id })
    }

    @Test
    fun `a corrupt store loads as empty instead of crashing`() {
        // The app must still open with a damaged preference.
        assertTrue(SavedRoutes.parse("{not json").routes.isEmpty())
        assertTrue(SavedRoutes.parse("").routes.isEmpty())
        assertTrue(SavedRoutes.parse("[{\"id\":1}]").routes.isEmpty())
    }

    @Test
    fun `an unknown field is ignored rather than failing the load`() {
        // Forward compatibility: a newer build may write a field this one does not know.
        val json = """[{"id":1,"name":"A","colour":"red","points":[[1.0,2.0],[1.1,2.1]]}]"""
        val routes = SavedRoutes.parse(json)
        assertEquals(1, routes.size)
        assertEquals("A", routes.routes.first().name)
    }

    @Test
    fun `sorting by name does not reorder the stored list`() {
        val routes = SavedRoutes()
        routes.save(draft(), "Zebra")
        routes.save(draft(), "apple")
        assertEquals(listOf("Zebra", "apple"), routes.routes.map { it.name })
        assertEquals(listOf("apple", "Zebra"), routes.sortedByName().map { it.name })
        assertEquals(listOf("Zebra", "apple"), routes.routes.map { it.name })
    }

    @Test
    fun `a loaded route is editable as a draft`() {
        val routes = SavedRoutes()
        val saved = (routes.save(draft(), "Loop") as SavedRoutes.SaveResult.Saved).route
        val draft = routes.draftOf(saved.id)
        assertNotNull(draft)
        assertEquals(2, draft!!.size)
        assertTrue("a loaded route must be runnable", draft.isValid)
    }
}

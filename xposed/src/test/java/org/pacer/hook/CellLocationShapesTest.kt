// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// CellLocationShapes is the single place the phone process, system_server and
// the app process all ask for the same fabricated cell identity, so the three
// cannot disagree the way the GPL reference implementation's three adaptations
// did. Its `fit` must also never hand a caller an object the caller's declared
// type cannot hold: the value crosses a Binder boundary and a mismatch throws
// at the far end.
//
// What this test can and cannot reach:
//  - It runs on the JVM against the SDK stub jar, whose android.* method bodies
//    throw. The fakes are therefore plain classes and reflection only.
//  - The android.os.Bundle branch of `fit` is NOT tested: constructing a Bundle
//    in an android unit test throws, and the branch is only reachable in a real
//    process (it is the caller declaring a Bundle type that triggers it). So no
//    test, only this note, for that branch.
//  - The android.telephony.CellLocation branch of `fit` is likewise not tested:
//    entering it requires a declared type naming the CellLocation class, which is
//    an Android type, and this file stays free of them. It is verified on a
//    device.
package org.pacer.hook

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.pacer.hook.hooks.env.CellLocationShapes

class CellLocationShapesTest {

    @Test
    fun servingIdentityReturnsNullForNullInput() {
        assertNull(
            "servingIdentity(null) must be null, not a fabricated cell: the hook " +
                "has no payload to take an identity from. A non-null result here " +
                "invents a cell where none exists.",
            CellLocationShapes.servingIdentity(null),
        )
    }

    @Test
    fun servingIdentityReturnsNullForAnEmptyList() {
        assertNull(
            "servingIdentity(emptyList) must be null: no entry can be the serving " +
                "cell. A non-null result here invents an identity from nothing.",
            CellLocationShapes.servingIdentity(emptyList()),
        )
    }

    @Test
    fun servingIdentityFallsBackToTheFirstCellWhenNoneReportsRegistered() {
        val firstIdentity = Any()
        val secondIdentity = Any()
        val cells = listOf(
            FakeCell(registered = false, identity = firstIdentity),
            FakeCell(registered = false, identity = secondIdentity),
        )
        assertSame(
            "When no cell reports itself registered the first cell must be the " +
                "serving identity. Picking any other cell (or null) here means the " +
                "phone process and the app process disagree about the serving cell " +
                "on a device with silent scanning.",
            firstIdentity,
            CellLocationShapes.servingIdentity(cells),
        )
    }

    @Test
    fun servingIdentityPicksTheCellThatReportsItselfRegistered() {
        val firstIdentity = Any()
        val registeredIdentity = Any()
        val cells = listOf(
            FakeCell(registered = false, identity = firstIdentity),
            FakeCell(registered = true, identity = registeredIdentity),
            FakeCell(registered = false, identity = Any()),
        )
        assertSame(
            "The first cell that reports isRegistered()==true must be the serving " +
                "identity, even when it is not the first entry in the list. " +
                "Ignoring the registration flag picks a cell the framework is not " +
                "serving, and getCellLocation() would then disagree with " +
                "getAllCellInfo().",
            registeredIdentity,
            CellLocationShapes.servingIdentity(cells),
        )
    }

    @Test
    fun servingIdentityReturnsNullWhenTheCellHasNoIdentityAccessor() {
        val cells = listOf(RegisteredCellWithoutIdentity())
        assertNull(
            "A serving cell with no getCellIdentity() member must yield null, not " +
                "throw: the lookup is reflective and the member may be absent in " +
                "some firmware. Throwing here would crash the hook.",
            CellLocationShapes.servingIdentity(cells),
        )
    }

    @Test
    fun servingIdentityReturnsNullWhenTheIdentityAccessorReturnsNull() {
        val cells = listOf(FakeCell(registered = true, identity = null))
        assertNull(
            "A serving cell whose getCellIdentity() returns null must yield null: " +
                "there is no identity to hand back, so the caller should see no " +
                "cell rather than an invented one.",
            CellLocationShapes.servingIdentity(cells),
        )
    }

    @Test
    fun fitReturnsTheIdentityItselfWhenTheDeclaredTypeIsNull() {
        val identity: Any = "the-single-fabricated-identity"
        assertSame(
            "fit(identity, null) returns the identity untouched: a null declared " +
                "type means the caller does not care which shape it is. Returning " +
                "anything else would wrap or reinterpret the identity the other two " +
                "callers asked for.",
            identity,
            CellLocationShapes.fit(identity, null),
        )
    }

    @Test
    fun fitReturnsTheIdentityWhenItIsAnInstanceOfTheDeclaredType() {
        val identity: Any = "the-single-fabricated-identity"
        assertSame(
            "An identity that already IS the declared type must come back as " +
                "itself; rebuilding it would be wasted work and could change its " +
                "value.",
            identity,
            CellLocationShapes.fit(identity, String::class.java),
        )
    }

    @Test
    fun fitReturnsTheIdentityForASupertypeOfTheIdentity() {
        val identity: Any = "the-single-fabricated-identity"
        assertSame(
            "An identity must be handed over in any declared type it is an " +
                "instance of, including a supertype. This is the same check that " +
                "keeps an unrelated type from receiving it.",
            identity,
            CellLocationShapes.fit(identity, CharSequence::class.java),
        )
    }

    @Test
    fun fitReturnsNullWhenTheIdentityIsNotAnInstanceOfTheDeclaredType() {
        // The critical guard: the value crosses a Binder boundary, so handing the
        // caller an object its declared parameter/return type cannot hold would
        // throw inside system_server or the phone process - a crash, not a failed
        // hook. Null is the intended answer.
        val identity: Any = "the-single-fabricated-identity"
        val result = CellLocationShapes.fit(identity, Thread::class.java)
        assertNull(
            "fit() must return null, not the identity, when the declared type does " +
                "not hold it - and must not throw. A mismatch is a crash at the far " +
                "end of Binder, so returning the identity here is the bug this " +
                "assertion exists to catch.",
            result,
        )
    }

    @Test
    fun asCellLocationReturnsNullForAnObjectWithNoCellLocationView() {
        assertNull(
            "An identity with no asCellLocation() member must yield null instead of " +
                "throwing: the hidden method is probed reflectively because it " +
                "exists on some but not all releases.",
            CellLocationShapes.asCellLocation(RegisteredCellWithoutIdentity()),
        )
    }

    @Test
    fun asCellLocationReturnsWhateverTheViewProvides() {
        val location = Any()
        val withView = FakeCellWithLocationView(location)
        assertSame(
            "asCellLocation() must return exactly what the identity's method " +
                "returned - no further rewriting, because the CellLocation view is " +
                "what the telephony caller declared. Returning a different object " +
                "here changes the value the framework reads back.",
            location,
            CellLocationShapes.asCellLocation(withView),
        )
    }

    // Shape of the fakes, and why (mirrors HookUtils.noArgAccessor):
    //  - Production reads 'isRegistered' and 'getCellIdentity' through
    //    Class.getMethod, which returns only PUBLIC members. So the methods must be
    //    public, and a Kotlin `fun` is public by default - hence no `private`
    //    keyword on them. A private or internal method would be invisible and the
    //    production lookup would return null for every fake.
    //  - The declaring classes are `private` yet still resolve. They are nested in
    //    the test class, so the test can construct them, and a private nested class
    //    is not private at the JVM class-file level (it compiles to a package-
    //    private static nested class), from which getMethod still returns public
    //    members - confirmed on JDK 22. Had getMethod missed them, the fakes would
    //    have had to be top-level.
    //  - The identity is carried by a constructor parameter and exposed only
    //    through the named getters, so nothing about the fake can satisfy the
    //    reflective lookup by accident (no bean-style property of the same name).
    private class FakeCell(private val registered: Boolean, private val identity: Any?) {
        fun isRegistered(): Boolean = registered
        fun getCellIdentity(): Any? = identity
    }

    private class RegisteredCellWithoutIdentity {
        fun isRegistered(): Boolean = true
        // Deliberately no getCellIdentity() and no asCellLocation(), so each
        // missing-member path is exercised: getMethod on 'getCellIdentity' and
        // declaredMethods scan on 'asCellLocation' both come up empty.
    }

    private class FakeCellWithLocationView(private val location: Any) {
        fun asCellLocation(): Any = location
    }
}

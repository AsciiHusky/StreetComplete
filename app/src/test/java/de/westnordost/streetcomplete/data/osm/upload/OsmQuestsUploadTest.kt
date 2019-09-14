package de.westnordost.streetcomplete.data.osm.upload

import de.westnordost.osmapi.map.data.Element
import de.westnordost.osmapi.map.data.OsmLatLon
import de.westnordost.osmapi.map.data.OsmNode
import de.westnordost.streetcomplete.any
import de.westnordost.streetcomplete.data.QuestStatus
import de.westnordost.streetcomplete.data.osm.*
import de.westnordost.streetcomplete.data.osm.changes.StringMapChanges
import de.westnordost.streetcomplete.data.osm.changes.StringMapEntryAdd
import de.westnordost.streetcomplete.data.osm.download.ElementGeometryCreator
import de.westnordost.streetcomplete.data.osm.persist.ElementGeometryDao
import de.westnordost.streetcomplete.data.osm.persist.MergedElementDao
import de.westnordost.streetcomplete.data.osm.persist.OsmQuestDao
import de.westnordost.streetcomplete.data.statistics.QuestStatisticsDao
import de.westnordost.streetcomplete.data.tiles.DownloadedTilesDao
import de.westnordost.streetcomplete.data.upload.OnUploadedChangeListener
import de.westnordost.streetcomplete.on
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class OsmQuestsUploadTest {
    private lateinit var questDB: OsmQuestDao
    private lateinit var elementDB: MergedElementDao
    private lateinit var changesetManager: OpenQuestChangesetsManager
    private lateinit var elementGeometryDB: ElementGeometryDao
    private lateinit var questGiver: OsmQuestGiver
    private lateinit var statisticsDB: QuestStatisticsDao
    private lateinit var elementGeometryCreator: ElementGeometryCreator
    private lateinit var singleChangeUpload: SingleOsmElementTagChangesUpload
    private lateinit var downloadedTilesDao: DownloadedTilesDao
    private lateinit var uploader: OsmQuestsUpload

    @Before fun setUp() {
        questDB = mock(OsmQuestDao::class.java)
        elementDB = mock(MergedElementDao::class.java)
        on(elementDB.get(any(), anyLong())).thenReturn(createElement())
        changesetManager = mock(OpenQuestChangesetsManager::class.java)
        singleChangeUpload = mock(SingleOsmElementTagChangesUpload::class.java)
        elementGeometryDB = mock(ElementGeometryDao::class.java)
        questGiver = mock(OsmQuestGiver::class.java)
        on(questGiver.updateQuests(any())).thenReturn(OsmQuestGiver.QuestUpdates(listOf(), listOf()))
        statisticsDB = mock(QuestStatisticsDao::class.java)
        elementGeometryCreator = mock(ElementGeometryCreator::class.java)
        on(elementGeometryCreator.create(any<Element>())).thenReturn(mock(ElementGeometry::class.java))
        downloadedTilesDao = mock(DownloadedTilesDao::class.java)
        uploader = OsmQuestsUpload(elementDB, elementGeometryDB, changesetManager, questGiver,
            statisticsDB, elementGeometryCreator, questDB, singleChangeUpload, downloadedTilesDao)
    }

    @Test fun `cancel upload works`() {
        uploader.upload(AtomicBoolean(true))
        verifyZeroInteractions(changesetManager, singleChangeUpload, elementDB, questDB)
    }

    @Test fun `catches ElementConflict exception`() {
        on(questDB.getAll(null, QuestStatus.ANSWERED)).thenReturn(listOf(createQuest()))
        on(singleChangeUpload.upload(anyLong(), any(), any()))
            .thenThrow(ElementConflictException())

        uploader.upload(AtomicBoolean(false))

        // will not throw ElementConflictException
    }

    @Test fun `discard if element was deleted`() {
        on(questDB.getAll(null, QuestStatus.ANSWERED)).thenReturn(listOf(createQuest()))
        on(elementDB.get(any(), anyLong())).thenReturn(null)

        uploader.uploadedChangeListener = mock(OnUploadedChangeListener::class.java)
        uploader.upload(AtomicBoolean(false))

        verify(uploader.uploadedChangeListener)?.onDiscarded()
    }

    @Test fun `catches ChangesetConflictException exception and tries again once`() {
        on(questDB.getAll(null, QuestStatus.ANSWERED)).thenReturn(listOf(createQuest()))
        on(singleChangeUpload.upload(anyLong(), any(), any()))
            .thenThrow(ChangesetConflictException())
            .thenReturn(createElement())

        uploader.upload(AtomicBoolean(false))

        // will not throw ChangesetConflictException but instead call single upload twice
        verify(changesetManager).getOrCreateChangeset(any(), any())
        verify(changesetManager).createChangeset(any(), any())
        verify(singleChangeUpload, times(2)).upload(anyLong(), any(), any())
    }

    @Test fun `close each uploaded quest in local DB and call listener`() {
        val quests = listOf( createQuest(), createQuest())

        on(questDB.getAll(null, QuestStatus.ANSWERED)).thenReturn(quests)
        on(singleChangeUpload.upload(anyLong(), any(), any())).thenReturn(createElement())

        uploader.uploadedChangeListener = mock(OnUploadedChangeListener::class.java)
        uploader.upload(AtomicBoolean(false))

        for (quest in quests) {
            assertEquals(QuestStatus.CLOSED, quest.status)
        }
        verify(questDB, times(2)).update(any())
        verify(uploader.uploadedChangeListener, times(2))?.onUploaded()
        verify(elementDB, times(2)).put(any())
        verify(elementGeometryDB, times(2)).put(any())
        verify(questGiver, times(2)).updateQuests(any())
        verifyZeroInteractions(downloadedTilesDao)
    }

    @Test fun `delete each unsuccessful upload from local DB and call listener`() {
        val quests = listOf( createQuest(), createQuest())

        on(questDB.getAll(null, QuestStatus.ANSWERED)).thenReturn(quests)
        on(singleChangeUpload.upload(anyLong(), any(), any()))
            .thenThrow(ElementConflictException())

        uploader.uploadedChangeListener = mock(OnUploadedChangeListener::class.java)
        uploader.upload(AtomicBoolean(false))

        verify(questDB, times(2)).delete(anyLong())
        verify(uploader.uploadedChangeListener, times(2))?.onDiscarded()
        verify(downloadedTilesDao, times(2)).remove(any())
        verifyZeroInteractions(questGiver, elementGeometryCreator)
    }

    @Test fun `delete unreferenced elements and clean metadata at the end`() {
        val quest = createQuest()

        on(questDB.getAll(null, QuestStatus.ANSWERED)).thenReturn(listOf(quest))
        on(singleChangeUpload.upload(anyLong(), any(), any())).thenReturn(createElement())

        uploader.upload(AtomicBoolean(false))

        verify(elementGeometryDB).deleteUnreferenced()
        verify(elementDB).deleteUnreferenced()
        verify(quest.osmElementQuestType).cleanMetadata()
    }
}

private fun createQuest() : OsmQuest {
    val changes = StringMapChanges(listOf(StringMapEntryAdd("surface","asphalt")))
    val geometry = ElementPointGeometry(OsmLatLon(0.0,0.0))
    val questType = mock(OsmElementQuestType::class.java)
    return OsmQuest(1L, questType, Element.Type.NODE, 1L, QuestStatus.ANSWERED, changes, "survey",
        Date(), geometry)
}

private fun createElement() = OsmNode(1,1,OsmLatLon(0.0,0.0),null)
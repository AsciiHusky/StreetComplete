package de.westnordost.streetcomplete.quests.building_type

import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.osmquest.SimpleOverpassQuestType
import de.westnordost.streetcomplete.data.osm.changes.StringMapChangesBuilder
import de.westnordost.streetcomplete.data.osm.mapdata.OverpassMapDataAndGeometryApi

class AddBuildingType (o: OverpassMapDataAndGeometryApi) : SimpleOverpassQuestType<String>(o) {

    // in the case of man_made, historic, military and power, these tags already contain
    // information about the purpose of the building, so no need to force asking it
    // same goes (more or less) for tourism, amenity, leisure. See #1854, #1891
    override val tagFilters = """
        ways, relations with building = yes
         and !man_made
         and !historic
         and !military
         and !power
         and !tourism
         and !attraction
         and !amenity
         and !leisure
         and location != underground
         and abandoned != yes
         and ruins != yes and ruined != yes
    """
    override val commitMessage = "Add building types"
    override val wikiLink = "Key:building"
    override val icon = R.drawable.ic_quest_building

    override fun getTitle(tags: Map<String, String>) = R.string.quest_buildingType_title

    override fun createForm() = AddBuildingTypeForm()

    override fun applyAnswerTo(answer: String, changes: StringMapChangesBuilder) {
        if(answer.startsWith("man_made=")) {
            val manMade = answer.split("=")[1]
            changes.delete("building")
            changes.add("man_made", manMade)
        } else {
            changes.modify("building", answer)
        }
    }
}

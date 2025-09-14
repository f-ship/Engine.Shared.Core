package ship.f.engine.shared.sdui2

import ship.f.engine.shared.core.Dependency
import ship.f.engine.shared.utils.serverdrivenui2.client.CommonClient2.Companion.create

class CommonClientDependency2(projectName: String? = null) : Dependency() {
    val client = create(projectName)
}
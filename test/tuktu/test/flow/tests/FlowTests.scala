package tuktu.test.flow.tests

import org.scalatest.DoNotDiscover
import org.scalatestplus.play.PlaySpec

import play.api.Play.current
import play.api.libs.concurrent.Akka
import tuktu.api.DataPacket
import tuktu.test.flow.BaseFlowTester

@DoNotDiscover
class FlowTests extends PlaySpec {
    "DummyTest flow" must {
        "generate one simple value" in {
            val data = List(DataPacket(List(Map("test" -> "test"))))
            new BaseFlowTester(Akka.system)(List(data), "flowtests/dummy")
        }
    }

    /*"Normalization flow" must {
        "normalize values to range [-1, 1]" in {
            val data = List(DataPacket(List(
                Map("data" -> 0.6),
                Map("data" -> 1.0),
                Map("data" -> -1.0),
                Map("data" -> -0.6))))
            new BaseFlowTester(Akka.system)(List(data), "flowtests/normalization")
        }
    }*/
}
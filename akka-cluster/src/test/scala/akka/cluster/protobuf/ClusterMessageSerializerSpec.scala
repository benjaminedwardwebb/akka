/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.protobuf

import akka.cluster._
import akka.actor.{ ActorSystem, Address, ExtendedActorSystem }
import akka.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import akka.routing.RoundRobinPool

import collection.immutable.SortedSet
import akka.testkit.{ AkkaSpec, TestKit }

class ClusterMessageSerializerSpec extends AkkaSpec(
  "akka.actor.provider = cluster") {

  val serializer = new ClusterMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

  def checkSerialization(obj: AnyRef): Unit = {
    val blob = serializer.toBinary(obj)
    val ref = serializer.fromBinary(blob, obj.getClass)
    obj match {
      case env: GossipEnvelope ⇒
        val env2 = obj.asInstanceOf[GossipEnvelope]
        env2.from should ===(env.from)
        env2.to should ===(env.to)
        env2.gossip should ===(env.gossip)
      case _ ⇒
        ref should ===(obj)
    }

  }

  import MemberStatus._

  val a1 = TestMember(Address("akka.tcp", "sys", "a", 2552), Joining, Set.empty)
  val b1 = TestMember(Address("akka.tcp", "sys", "b", 2552), Up, Set("r1"))
  val c1 = TestMember(Address("akka.tcp", "sys", "c", 2552), Leaving, Set("r2"))
  val d1 = TestMember(Address("akka.tcp", "sys", "d", 2552), Exiting, Set("r1", "r2"))
  val e1 = TestMember(Address("akka.tcp", "sys", "e", 2552), Down, Set("r3"))
  val f1 = TestMember(Address("akka.tcp", "sys", "f", 2552), Removed, Set("r2", "r3"))

  "ClusterMessages" must {

    "be serializable" in {
      val address = Address("akka.tcp", "system", "some.host.org", 4711)
      val uniqueAddress = UniqueAddress(address, 17L)
      val address2 = Address("akka.tcp", "system", "other.host.org", 4711)
      val uniqueAddress2 = UniqueAddress(address2, 18L)
      checkSerialization(InternalClusterAction.Join(uniqueAddress, Set("foo", "bar")))
      checkSerialization(ClusterUserAction.Leave(address))
      checkSerialization(ClusterUserAction.Down(address))
      checkSerialization(InternalClusterAction.InitJoin)
      checkSerialization(InternalClusterAction.InitJoinAck(address))
      checkSerialization(InternalClusterAction.InitJoinNack(address))
      checkSerialization(ClusterHeartbeatSender.Heartbeat(address))
      checkSerialization(ClusterHeartbeatSender.HeartbeatRsp(uniqueAddress))
      checkSerialization(InternalClusterAction.ExitingConfirmed(uniqueAddress))

      val node1 = VectorClock.Node("node1")
      val node2 = VectorClock.Node("node2")
      val node3 = VectorClock.Node("node3")
      val node4 = VectorClock.Node("node4")
      val g1 = (Gossip(SortedSet(a1, b1, c1, d1)) :+ node1 :+ node2).seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      val g2 = (g1 :+ node3 :+ node4).seen(a1.uniqueAddress).seen(c1.uniqueAddress)
      val reachability3 = Reachability.empty.unreachable(a1.uniqueAddress, e1.uniqueAddress).unreachable(b1.uniqueAddress, e1.uniqueAddress)
      val g3 = g2.copy(members = SortedSet(a1, b1, c1, d1, e1), overview = g2.overview.copy(reachability = reachability3))
      checkSerialization(GossipEnvelope(a1.uniqueAddress, uniqueAddress2, g1))
      checkSerialization(GossipEnvelope(a1.uniqueAddress, uniqueAddress2, g2))
      checkSerialization(GossipEnvelope(a1.uniqueAddress, uniqueAddress2, g3))

      checkSerialization(GossipStatus(a1.uniqueAddress, g1.version))
      checkSerialization(GossipStatus(a1.uniqueAddress, g2.version))
      checkSerialization(GossipStatus(a1.uniqueAddress, g3.version))

      checkSerialization(InternalClusterAction.Welcome(uniqueAddress, g2))
    }

    "be compatible with wire format of version 2.5.3 (using use-role instead of use-roles)" in {
      val system = ActorSystem("ClusterMessageSerializer-old-wire-format")

      try {
        val serializer = new ClusterMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

        // the oldSnapshot was created with the version of ClusterRouterPoolSettings in Akka 2.5.3. See issue #23257.
        // It was created with:
        /*
          import org.apache.commons.codec.binary.Hex.encodeHex
          val bytes = serializer.toBinary(
            ClusterRouterPool(RoundRobinPool(nrOfInstances = 4), ClusterRouterPoolSettings(123, 345, true, Some("role ABC"))))
          println(String.valueOf(encodeHex(bytes)))
        */

        val oldBytesHex = "0a0f08101205524f5252501a04080418001211087b10d90218012208726f6c6520414243"

        import org.apache.commons.codec.binary.Hex.decodeHex
        val oldBytes = decodeHex(oldBytesHex.toCharArray)
        val result = serializer.fromBinary(oldBytes, classOf[ClusterRouterPool])

        result match {
          case pool: ClusterRouterPool ⇒
            pool.settings.totalInstances should ===(123)
            pool.settings.maxInstancesPerNode should ===(345)
            pool.settings.allowLocalRoutees should ===(true)
            pool.settings.useRole should ===(Some("role ABC"))
            pool.settings.useRoles should ===(Set("role ABC"))
        }
      } finally {
        TestKit.shutdownActorSystem(system)
      }

    }
  }
  "Cluster router pool" must {
    "be serializable with no role" in {
      checkSerialization(ClusterRouterPool(
        RoundRobinPool(
          nrOfInstances = 4
        ),
        ClusterRouterPoolSettings(
          totalInstances = 2,
          maxInstancesPerNode = 5,
          allowLocalRoutees = true
        )
      ))
    }

    "be serializable with one role" in {
      checkSerialization(ClusterRouterPool(
        RoundRobinPool(
          nrOfInstances = 4
        ),
        ClusterRouterPoolSettings(
          totalInstances = 2,
          maxInstancesPerNode = 5,
          allowLocalRoutees = true,
          useRoles = Set("Richard, Duke of Gloucester")
        )
      ))
    }

    "be serializable with many roles" in {
      checkSerialization(ClusterRouterPool(
        RoundRobinPool(
          nrOfInstances = 4
        ),
        ClusterRouterPoolSettings(
          totalInstances = 2,
          maxInstancesPerNode = 5,
          allowLocalRoutees = true,
          useRoles = Set("Richard, Duke of Gloucester", "Hongzhi Emperor", "Red Rackham")
        )
      ))
    }
  }

}
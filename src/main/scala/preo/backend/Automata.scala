package preo.backend

import preo.ast.{CPrim, CoreConnector, CoreInterface}
import ReoGraph._
import preo.backend.Automata.Trans
import preo.common.TypeCheckException

/**
  * Representation of an automata, aimed at being generated from a [[ReoGraph]].
  * @param ports Represent the possible labels (actions)
  * @param init Initial state
  * @param trans Transitions - Relation between input and output states, with associated
  *              sets of actions and of edges (as in [[ReoGraph.Edge]]).
  */
case class Automata(ports:Set[Int],init:Int,trans:Trans) {

 def getStates: Set[Int] = (for((x,(y,_,_)) <- trans) yield Set(x,y)).flatten
  // states: ints, transitions: maps from states to (new state,ports fired, primitives involved)

  /**
    * Automata composition - combining every possible transition,
    * and including transitions that can occur in parallel.
    * @param other automata to be composed
    * @return composed automata
    */
  def ++(other:Automata): Automata = {
//     println(s"combining ${this.show}\nwith ${other.show}")
    var seed = 0
    val shared = other.ports intersect ports
    var restrans = Set[(Int,(Int,Set[Int],Set[Edge]))]()
    var newStates = Map[(Int,Int),Int]()
    def mkState(i1:Int,i2:Int) = if (newStates.contains((i1,i2)))
        newStates((i1,i2))
      else {
        seed +=1
        newStates += (i1,i2) -> seed
        seed
      }
    def ok(toFire:Set[Int]): Boolean = toFire.intersect(shared).isEmpty
    def ok2(toFire1:Set[Int],toFire2:Set[Int]): Boolean =
      toFire1.intersect(other.ports) == toFire2.intersect(ports)

    // just 1
    for ((from1,(to1,fire1,es1)) <- trans; p2 <- other.getStates)
      if (ok(fire1))
        restrans += mkState(from1,p2) -> (mkState(to1,p2),fire1,es1)
    // just 2
    for ((from2,(to2,fire2,es2)) <- other.trans; p1 <- getStates)
      if (ok(fire2))
        restrans += mkState(p1,from2) -> (mkState(p1,to2),fire2,es2)
    // communication
    for ((from1,(to1,fire1,es1)) <- trans; (from2,(to2,fire2,es2)) <- other.trans) {
      if (ok2(fire1,fire2))
        restrans += mkState(from1,from2) -> (mkState(to1,to2),fire1++fire2,es1++es2)
    }
    val allStates = newStates.values.toSet
    // println(s"ports: $newStates")
    // cleanup before?
    val a = Automata(ports++other.ports,mkState(init,other.init),restrans)
//    println(s"got ${a.show}")
    val a2 = a.cleanup
//    println(s"cleaned ${a2.show}")
    a2
  }

  def cleanup: Automata = {
    var missing = Set(init)
    var done = Set[Int]()
    var ntrans:Trans = Set()
    while (missing.nonEmpty) {
      val next = missing.head
      missing = missing.tail
      done += next
      for (t@(from,(to,_,_)) <- trans if from==next) {
        ntrans += t
        if (!(done contains to)) missing += to
      }
    }
    Automata(ports,init,ntrans)
  }

  @deprecated("starting experiment to produce automata/graphs","jan 2018")
  def toGraph: ReoGraph = {
    var s = 0
    var edges = List[Edge]()
    for ((from,(to,fire,es1)) <- trans.toList) {
      s -= 2
      edges ::= Edge(CPrim(fire.mkString(".")+(if(fire.nonEmpty)"-" else "")+
          es1.map(x =>
              x.prim.name+x.ins.mkString("")+"."+x.outs.mkString("")).mkString("-"),
        preo.ast.CoreInterface(0),preo.ast.CoreInterface(0),None),
        List(s,s+1),List())
      edges ::= Edge(CPrim("",
          preo.ast.CoreInterface(0), preo.ast.CoreInterface(0), None),
          List(from,s), List())
      edges ::= Edge(CPrim("",
        preo.ast.CoreInterface(0), preo.ast.CoreInterface(0), None),
        List(s+1), List(to))
    }
    ReoGraph(edges,Nil,Nil)
  }

  def show: String =
    s"$init:\n"+trans.map(x=>s" - ${x._1}->${x._2._1} "+
      s"${x._2._2.mkString("[",",","]")} "+
      s"${x._2._3.map(_.prim.name).mkString("(",",",")")}").mkString("\n")
}

object Automata {

  type Trans = Set[(Int,(Int,Set[Int],Set[Edge]))]
  private var seed = 0

  def apply(str:String): Automata = {
    val c = preo.DSL.parse(str)
    val cc = preo.DSL.reduce(c)
    apply(cc)
  }

  def apply(cc:CoreConnector): Automata = {
    seed = 0
    val gr = ReoGraph.toGraphWithoutSimpl(cc)
//    println("about to create automata from\n"+gr)
    buildAutomata(gr)
  }

  private def buildAutomata(e:Edge): Automata = e match {
    case Edge(CPrim("sync",i,j,extra), List(a), List(b)) =>
      seed += 1
      Automata(Set(a,b),seed,Set(seed -> (seed,Set(a,b),Set(e))))
    case Edge(CPrim("id",i,j,extra), List(a), List(b)) =>
      seed += 1
      Automata(Set(a,b),seed,Set(seed -> (seed,Set(a,b),Set(e))))
    case Edge(CPrim("lossy",i,j,extra), List(a), List(b)) =>
      seed += 1
      Automata(Set(a,b),seed,Set(seed -> (seed,Set(a,b),Set(e)),seed -> (seed,Set(a),Set(e))))
    case Edge(CPrim("fifo",i,j,extra), List(a), List(b)) =>
      seed += 2
      Automata(Set(a,b),seed-1,Set(seed-1 -> (seed,Set(a),Set(e)),seed -> (seed-1,Set(b),Set(e))))
    case Edge(CPrim("fifofull",i,j,extra), List(a), List(b)) =>
      seed += 2
      Automata(Set(a,b),seed,Set(seed-1 -> (seed,Set(a),Set(e)),seed -> (seed-1,Set(b),Set(e))))
    case Edge(CPrim("drain",i,j,extra), List(a,b), List()) =>
      seed += 1
      Automata(Set(a,b),seed,Set(seed -> (seed,Set(a,b),Set(e))))
    case Edge(CPrim("merger",i,j,extra), List(a,b), List(c)) =>
      seed += 1
      Automata(Set(a,b,c),seed,Set(seed -> (seed,Set(a,c),Set(e)),seed -> (seed,Set(b,c),Set(e))))
    case Edge(CPrim("dupl",i,j,extra), List(a), List(b,c)) =>
      seed += 1
      Automata(Set(a,b,c),seed,Set(seed -> (seed,Set(a,b,c),Set(e))))
    case Edge(CPrim("writer",i,j,extra), List(), List(a)) =>
      seed += 1
      Automata(Set(a),seed,Set(seed -> (seed,Set(a),Set(e))))
    case Edge(CPrim("reader",i,j,extra), List(a), List()) =>
      seed += 1
      Automata(Set(a),seed,Set(seed -> (seed,Set(a),Set(e))))

    case Edge(p, _, _) =>
      throw new TypeCheckException(s"Unknown automata for primitive $p")
  }

  /**
    * Build automata by starting at a random edge, and follow neighbours.
    * @param g
    * @return
    */
  private def buildAutomata(g: ReoGraph): Automata = {
    val (ins,outs) = collectInsOuts(g)
    def getNeighbours(e:Edge): List[Edge] =
      (for (i <- e.ins)  yield outs.getOrElse(i,Set())).flatten ++
      (for (o <- e.outs) yield ins.getOrElse(o,Set())).flatten


    if (g.edges.nonEmpty) {
      var prev = g.edges.head
      var missing = g.edges.toSet - prev
//      println(s"next: ${prev.prim.name} ${prev.ins} ${prev.outs} ${prev.priority}")
      var aut = buildAutomata(prev)
      var next = getNeighbours(prev)
      //    var next = if (g.ins.nonEmpty) ins(g.ins.head)
      //    for (in <- g.ins.headOption; set <- ins.get(in); e <- )

      while (missing.nonEmpty) {
        while (next.nonEmpty) {
          // pop "prev" from "next"
          prev = next.head
          next = next.tail
//          println(s"next: ${prev.prim.name} ${prev.ins} ${prev.outs} ${prev.priority}")
          aut = aut ++ buildAutomata(prev) // update automata with "prev"
          missing -= prev // add "prev" to known edges
        }
        if (missing.nonEmpty) {
          prev = missing.head
//          println(s"next: ${prev.prim.name} ${prev.ins} ${prev.outs} ${prev.priority}")
          aut = aut ++ buildAutomata(prev)
          next = getNeighbours(prev)
          missing = missing.tail
        }
      }
      aut
    }
    else
      Automata(Set(),0,Set())
  }
}

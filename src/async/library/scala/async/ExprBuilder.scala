/**
 * Copyright (C) 2012 Typesafe Inc. <http://www.typesafe.com>
 */
package scala.async

import scala.reflect.macros.Context
import scala.collection.mutable.{ ListBuffer, Builder }

/*
 * @author Philipp Haller
 */
class ExprBuilder[C <: Context with Singleton](val c: C) extends AsyncUtils {
  builder =>
  
  import c.universe._
  import Flag._
  
  private val awaitMethod = awaitSym(c)
  
  /* Make a partial function literal handling case #num:
   * 
   *     {
   *       case any if any == num => rhs
   *     }
   */
  def mkHandler(num: Int, rhs: c.Expr[Unit]): c.Expr[PartialFunction[Int, Unit]] = {
/*
    val numLiteral = c.Expr[Int](Literal(Constant(num)))
    
    reify(new PartialFunction[Int, Unit] {
      def isDefinedAt(`x$1`: Int) =
        `x$1` == numLiteral.splice
      def apply(`x$1`: Int) = `x$1` match {
        case any: Int if any == numLiteral.splice =>
          rhs.splice
      }
    })
*/
    val rhsTree = c.resetAllAttrs(rhs.tree.duplicate)
    val handlerTree = mkHandlerTree(num, rhsTree)
    c.Expr(handlerTree).asInstanceOf[c.Expr[PartialFunction[Int, Unit]]]
  }
  
  def mkIncrStateTree(): c.Tree =
    Assign(
      Ident(newTermName("state")),
      Apply(Select(Ident(newTermName("state")), newTermName("$plus")), List(Literal(Constant(1)))))
  
  def mkStateTree(nextState: Int): c.Tree =
    Assign(
      Ident(newTermName("state")),
      Literal(Constant(nextState)))
  
  def mkVarDefTree(resultType: c.universe.Type, resultName: c.universe.TermName): c.Tree = {
    val rhs =
      if (resultType <:< definitions.IntTpe) Literal(Constant(0))
      else if (resultType <:< definitions.LongTpe) Literal(Constant(0L))
      else if (resultType <:< definitions.BooleanTpe) Literal(Constant(false))
      else Literal(Constant(null))
    ValDef(Modifiers(Flag.MUTABLE), resultName, TypeTree(resultType), rhs)
  }
  
  def mkHandlerTree(num: Int, rhs: c.Tree): c.Tree = {
    val partFunIdent = Ident(c.mirror.staticClass("scala.PartialFunction"))
    val intIdent = Ident(definitions.IntClass)
    val unitIdent = Ident(definitions.UnitClass)
    
    Block(List(
      // anonymous subclass of PartialFunction[Int, Unit]
      ClassDef(Modifiers(FINAL), newTypeName("$anon"), List(), Template(List(AppliedTypeTree(partFunIdent, List(intIdent, unitIdent))),
        emptyValDef, List(

          DefDef(Modifiers(), nme.CONSTRUCTOR, List(), List(List()), TypeTree(),
            Block(List(Apply(Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR), List())), Literal(Constant(())))),

          DefDef(Modifiers(), newTermName("isDefinedAt"), List(), List(List(ValDef(Modifiers(PARAM), newTermName("x$1"), intIdent, EmptyTree))), TypeTree(),
            Apply(Select(Ident(newTermName("x$1")), newTermName("$eq$eq")), List(Literal(Constant(num))))),
          
          DefDef(Modifiers(), newTermName("apply"), List(), List(List(ValDef(Modifiers(PARAM), newTermName("x$1"), intIdent, EmptyTree))), TypeTree(),
            Match(Ident(newTermName("x$1")), List(
              CaseDef(
                // pattern
                Bind(newTermName("any"), Typed(Ident(nme.WILDCARD), intIdent)),
                // guard
                Apply(Select(Ident(newTermName("any")), newTermName("$eq$eq")), List(Literal(Constant(num)))),
                rhs
              )
            ))
          )
          
        ))
      )),
      Apply(Select(New(Ident(newTypeName("$anon"))), nme.CONSTRUCTOR), List())
    )
  }
  
  class AsyncState(stats: List[c.Tree], val state: Int, val nextState: Int) {
    val body: c.Tree =
      if (stats.size == 1) stats.head
      else Block(stats: _*)
    
    val varDefs: List[(c.universe.TermName, c.universe.Type)] = List()
    
    def mkHandlerTreeForState(): c.Tree =
      mkHandlerTree(state, Block((stats :+ mkStateTree(nextState) :+ Apply(Ident("resume"), List())): _*))
    
    def mkHandlerTreeForState(nextState: Int): c.Tree =
      mkHandlerTree(state, Block((stats :+ mkStateTree(nextState) :+ Apply(Ident("resume"), List())): _*))
    
    def varDefForResult: Option[c.Tree] =
      None
    
    def allVarDefs: List[c.Tree] =
      varDefForResult.toList ++ varDefs.map(p => mkVarDefTree(p._2, p._1))
    
    override val toString: String =
      s"AsyncState #$state, next = $nextState"
  }
  
  class AsyncStateWithIf(stats: List[c.Tree], state: Int)
      extends AsyncState(stats, state, 0) { // nextState unused, since encoded in then and else branches
    
    override def mkHandlerTreeForState(): c.Tree =
      mkHandlerTree(state, Block(stats: _*))
    
    //TODO mkHandlerTreeForState(nextState: Int)
    
    override val toString: String =
      s"AsyncStateWithIf #$state, next = $nextState"
  }
  
  abstract class AsyncStateWithAwait(stats: List[c.Tree], state: Int, nextState: Int)
      extends AsyncState(stats, state, nextState) {
    val awaitable: c.Tree
    val resultName: c.universe.TermName
    val resultType: c.universe.Type
    
    override val toString: String =
      s"AsyncStateWithAwait #$state, next = $nextState"
    
    /* Make an `onComplete` invocation:
     * 
     *     awaitable.onComplete {
     *       case tr =>
     *         resultName = tr.get
     *         resume()
     *     }
     */
    def mkOnCompleteTree: c.Tree = {
      val assignTree =
        Assign(
          Ident(resultName.toString),
          Select(Ident("tr"), c.universe.newTermName("get"))
        )
      val handlerTree =
        Match(
          EmptyTree,
          List(
            CaseDef(Bind(c.universe.newTermName("tr"), Ident("_")), EmptyTree,
              Block(assignTree, Apply(Ident("resume"), List())) // rhs of case
            )
          )
        )
      Apply(
        Select(awaitable, c.universe.newTermName("onComplete")),
        List(handlerTree)
      )
    }
    
    /* Make an `onComplete` invocation which increments the state upon resuming:
     * 
     *     awaitable.onComplete {
     *       case tr =>
     *         resultName = tr.get
     *         state += 1
     *         resume()
     *     }
     */
    def mkOnCompleteIncrStateTree: c.Tree = {
      val tryGetTree =
        Assign(
          Ident(resultName.toString),
          Select(Ident("tr"), c.universe.newTermName("get"))
        )
      val handlerTree =
        Match(
          EmptyTree,
          List(
            CaseDef(Bind(c.universe.newTermName("tr"), Ident("_")), EmptyTree,
              Block(tryGetTree, mkIncrStateTree(), Apply(Ident("resume"), List())) // rhs of case
            )
          )
        )
      Apply(
        Select(awaitable, c.universe.newTermName("onComplete")),
        List(handlerTree)
      )
    }

    /* Make an `onComplete` invocation which sets the state to `nextState` upon resuming:
     * 
     *     awaitable.onComplete {
     *       case tr =>
     *         resultName = tr.get
     *         state = `nextState`
     *         resume()
     *     }
     */
    def mkOnCompleteStateTree(nextState: Int): c.Tree = {
      val tryGetTree =
        Assign(
          Ident(resultName.toString),
          Select(Ident("tr"), c.universe.newTermName("get"))
        )
      val handlerTree =
        Match(
          EmptyTree,
          List(
            CaseDef(Bind(c.universe.newTermName("tr"), Ident("_")), EmptyTree,
              Block(tryGetTree, mkStateTree(nextState), Apply(Ident("resume"), List())) // rhs of case
            )
          )
        )
      Apply(
        Select(awaitable, c.universe.newTermName("onComplete")),
        List(handlerTree)
      )
    }

    /* Make a partial function literal handling case #num:
     * 
     *     {
     *       case any if any == num =>
     *         stats
     *         awaitable.onComplete {
     *           case tr =>
     *             resultName = tr.get
     *             resume()
     *         }
     *     }
     */
    def mkHandlerForState(num: Int): c.Expr[PartialFunction[Int, Unit]] = {
      assert(awaitable != null)
      builder.mkHandler(num, c.Expr[Unit](Block((stats :+ mkOnCompleteTree): _*)))
    }
    
    /* Make a partial function literal handling case #num:
     * 
     *     {
     *       case any if any == num =>
     *         stats
     *         awaitable.onComplete {
     *           case tr =>
     *             resultName = tr.get
     *             state += 1
     *             resume()
     *         }
     *     }
     */
    override def mkHandlerTreeForState(): c.Tree = {
      assert(awaitable != null)
      mkHandlerTree(state, Block((stats :+ mkOnCompleteIncrStateTree): _*))
    }
    
    override def mkHandlerTreeForState(nextState: Int): c.Tree = {
      assert(awaitable != null)
      mkHandlerTree(state, Block((stats :+ mkOnCompleteStateTree(nextState)): _*))
    }
    
    override def varDefForResult: Option[c.Tree] = {
      val rhs =
        if (resultType <:< definitions.IntTpe) Literal(Constant(0))
        else if (resultType <:< definitions.LongTpe) Literal(Constant(0L))
        else if (resultType <:< definitions.BooleanTpe) Literal(Constant(false))
        else if (resultType <:< definitions.FloatTpe) Literal(Constant(0.0f))
        else if (resultType <:< definitions.DoubleTpe) Literal(Constant(0.0d))
        else if (resultType <:< definitions.CharTpe) Literal(Constant(0.toChar))
        else if (resultType <:< definitions.ShortTpe) Literal(Constant(0.toShort))
        else if (resultType <:< definitions.ByteTpe) Literal(Constant(0.toByte))
        else Literal(Constant(null))
      Some(
        ValDef(Modifiers(Flag.MUTABLE), resultName, TypeTree(resultType), rhs)
      )
    }
  }
  
  /*
   * Builder for a single state of an async method.
   */
  class AsyncStateBuilder(state: Int, private var nameMap: Map[c.Symbol, c.Name]) extends Builder[c.Tree, AsyncState] {
    self =>
    
    /* Statements preceding an await call. */
    private val stats = ListBuffer[c.Tree]()
    
    /* Argument of an await call. */
    var awaitable: c.Tree = null
    
    /* Result name of an await call. */
    var resultName: c.universe.TermName = null
    
    /* Result type of an await call. */
    var resultType: c.universe.Type = null
    
    var nextState: Int = state + 1
    
    private val varDefs = ListBuffer[(c.universe.TermName, c.universe.Type)]()
    
    private val renamer = new Transformer {
      override def transform(tree: Tree) = tree match {
        case Ident(_) if nameMap.keySet contains tree.symbol =>
          Ident(nameMap(tree.symbol))
        case _ =>
          super.transform(tree)
      }
    }
    
    def += (stat: c.Tree): this.type = {
      stats += c.resetAllAttrs(renamer.transform(stat).duplicate)
      this
    }
    
    //TODO do not ignore `mods`
    def addVarDef(mods: Any, name: c.universe.TermName, tpt: c.Tree, rhs: c.Tree, extNameMap: Map[c.Symbol, c.Name]): this.type = {
      varDefs += (name -> tpt.tpe)
      nameMap ++= extNameMap // update name map
      this += Assign(Ident(name), c.resetAllAttrs(renamer.transform(rhs).duplicate))
      this
    }
    
    def result(): AsyncState =
      if (awaitable == null)
        new AsyncState(stats.toList, state, nextState) {
          override val varDefs = self.varDefs.toList
        }
      else
        new AsyncStateWithAwait(stats.toList, state, nextState) {
          val awaitable = self.awaitable
          val resultName = self.resultName
          val resultType = self.resultType
          override val varDefs = self.varDefs.toList
        }
    
    def clear(): Unit = {
      stats.clear()
      awaitable = null
      resultName = null
      resultType = null
    }
    
    /* Result needs to be created as a var at the beginning of the transformed method body, so that
     * it is visible in subsequent states of the state machine.
     *
     * @param awaitArg         the argument of await
     * @param awaitResultName  the name of the variable that the result of await is assigned to
     * @param awaitResultType  the type of the result of await
     */
    def complete(awaitArg: c.Tree, awaitResultName: c.universe.TermName, awaitResultType: Tree, extNameMap: Map[c.Symbol, c.Name], nextState: Int = state + 1): this.type = {
      nameMap ++= extNameMap
      awaitable = c.resetAllAttrs(renamer.transform(awaitArg).duplicate)
      resultName = awaitResultName
      resultType = awaitResultType.tpe
      this.nextState = nextState
      this
    }
    
    def complete(nextState: Int): this.type = {
      this.nextState = nextState
      this
    }
    
    def resultWithIf(condTree: c.Tree, thenState: Int, elseState: Int): AsyncState = {
      // 1. build changed if-else tree
      // 2. insert that tree at the end of the current state
      val cond = c.resetAllAttrs(condTree.duplicate)
      this += If(cond,
          Block(mkStateTree(thenState), Apply(Ident("resume"), List())),
          Block(mkStateTree(elseState), Apply(Ident("resume"), List())))
      new AsyncStateWithIf(stats.toList, state) {
        override val varDefs = self.varDefs.toList
      }
    }
    
    override def toString: String = {
      val statsBeforeAwait = stats.mkString("\n")
      s"ASYNC STATE:\n$statsBeforeAwait \nawaitable: $awaitable \nresult name: $resultName"
    }
  }

  class AsyncBlockBuilder(stats: List[c.Tree], expr: c.Tree, startState: Int, endState: Int, budget: Int, private var toRename: Map[c.Symbol, c.Name]) {
    val asyncStates = ListBuffer[builder.AsyncState]()
    
    private var stateBuilder = new builder.AsyncStateBuilder(startState, toRename) // current state builder
    private var currState = startState
    
    private var remainingBudget = budget
    
    /* Fall back to CPS plug-in if tree contains an `await` call. */
    def checkForUnsupportedAwait(tree: c.Tree) = if (tree exists {
      case Apply(fun, _) if fun.symbol == awaitMethod => true
      case _ => false
    }) throw new FallbackToCpsException
    
    // populate asyncStates
    for (stat <- stats) stat match {
      // the val name = await(..) pattern
      case ValDef(mods, name, tpt, Apply(fun, args)) if fun.symbol == awaitMethod =>
        val newName = newTermName(Async.freshString(name.toString()))
        toRename += (stat.symbol -> newName)
        
        asyncStates += stateBuilder.complete(args(0), newName, tpt, toRename).result // complete with await
        if (remainingBudget > 0)
          remainingBudget -= 1
        else
          assert(false, "too many invocations of `await` in current method")
        currState += 1
        stateBuilder = new builder.AsyncStateBuilder(currState, toRename)
        
      case ValDef(mods, name, tpt, rhs) =>
        checkForUnsupportedAwait(rhs)
        
        val newName = newTermName(Async.freshString(name.toString()))
        toRename += (stat.symbol -> newName)
        // when adding assignment need to take `toRename` into account
        stateBuilder.addVarDef(mods, newName, tpt, rhs, toRename)
        
      case If(cond, thenp, elsep) =>
        checkForUnsupportedAwait(cond)
        
        val ifBudget: Int = remainingBudget / 2
        remainingBudget -= ifBudget //TODO test if budget > 0
        // state that we continue with after if-else: currState + ifBudget
        
        val thenBudget: Int = ifBudget / 2
        val elseBudget = ifBudget - thenBudget
        
        asyncStates +=
          // the two Int arguments are the start state of the then branch and the else branch, respectively
          stateBuilder.resultWithIf(cond, currState + 1, currState + thenBudget)
        
        val thenBuilder = thenp match {
          case Block(thenStats, thenExpr) =>
            new AsyncBlockBuilder(thenStats, thenExpr, currState + 1, currState + ifBudget, thenBudget, toRename)
          case _ =>
            new AsyncBlockBuilder(List(thenp), Literal(Constant(())), currState + 1, currState + ifBudget, thenBudget, toRename)
        }
        asyncStates ++= thenBuilder.asyncStates
        toRename ++= thenBuilder.toRename
        
        val elseBuilder = elsep match {
          case Block(elseStats, elseExpr) =>
            new AsyncBlockBuilder(elseStats, elseExpr, currState + thenBudget, currState + ifBudget, elseBudget, toRename)
          case _ =>
            new AsyncBlockBuilder(List(elsep), Literal(Constant(())), currState + thenBudget, currState + ifBudget, elseBudget, toRename)
        }
        asyncStates ++= elseBuilder.asyncStates
        toRename ++= elseBuilder.toRename
        
        // create new state builder for state `currState + ifBudget`
        currState = currState + ifBudget
        stateBuilder = new builder.AsyncStateBuilder(currState, toRename)
        
      case _ =>
        checkForUnsupportedAwait(stat)
        stateBuilder += stat
    }
    // complete last state builder (representing the expressions after the last await)
    stateBuilder += expr
    val lastState = stateBuilder.complete(endState).result
    asyncStates += lastState
    
    /* Builds the handler expression for a sequence of async states.
     */
    def mkHandlerExpr(): c.Expr[PartialFunction[Int, Unit]] = {
      assert(asyncStates.size > 1)
      
      var handlerTree =
        if (asyncStates.size > 2) asyncStates(0).mkHandlerTreeForState()
        else asyncStates(0).mkHandlerTreeForState()
      var handlerExpr =
        c.Expr(handlerTree).asInstanceOf[c.Expr[PartialFunction[Int, Unit]]]
      
      if (asyncStates.size == 2)
        handlerExpr
      else if (asyncStates.size == 3) {
        val handlerTreeForLastState = asyncStates(1).mkHandlerTreeForState()
        val currentHandlerTreeNaked = c.resetAllAttrs(handlerExpr.tree.duplicate)
        c.Expr(
          Apply(Select(currentHandlerTreeNaked, newTermName("orElse")),
                List(handlerTreeForLastState))).asInstanceOf[c.Expr[PartialFunction[Int, Unit]]]
      } else { // asyncStates.size > 3
        // do not traverse first or last state:  asyncStates.tail.init
        for (asyncState <- asyncStates.tail.init) {
          val handlerTreeForNextState = asyncState.mkHandlerTreeForState()
          val currentHandlerTreeNaked = c.resetAllAttrs(handlerExpr.tree.duplicate)
          handlerExpr = c.Expr(
            Apply(Select(currentHandlerTreeNaked, newTermName("orElse")),
                  List(handlerTreeForNextState))).asInstanceOf[c.Expr[PartialFunction[Int, Unit]]]
        }
        handlerExpr
      }
    }

  }
}
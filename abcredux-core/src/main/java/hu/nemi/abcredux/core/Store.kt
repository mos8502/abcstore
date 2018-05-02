package hu.nemi.abcredux.core

import kotlin.properties.Delegates

/**
 * Action factory that can be dispathced to a [StateStore]. When the [ActionCreator] is dispatched the [StateStore] will invoke it with the current state.
 * The [ActionCreator] at this points may choose to return an action or null. If null is returned the state will not be changed
 */
interface ActionCreator<in S : Any, out A> {
    /**
     * Factory function for an action of type [A] give the current state of type [S]
     *
     * @param state the current state
     * @return tha action of type [A] to be dispatched or null
     */
    operator fun invoke(state: S): A?
}

/**
 * Asynchronous action creator. When dispatched to a [StateStore] the store will invoke it with the current state.
 * The [AsyncActionCreator] at this point may dispatch any number of [ActionCreator<S, A>] through the provided dispatch functions
 */
interface AsyncActionCreator<in S : Any, out A : Any?> {
    /**
     * Function to initiate asynchronous [ActionCreator] dispatching
     *
     * @param state the currentstate
     * @param dispatcher dispatcher function to dispatch [ActionCreator<S, A>]s
     */
    operator fun invoke(state: S, dispatcher: (ActionCreator<S, A>) -> Unit)
}

/**
 * Contract for action dispatchers.
 */
interface Dispatcher<in S, out R> {

    /**
     * Dispatches an action]
     *
     * @param action of type [S] to dispatch
     * @return arbitrary value returned by the dispatcher
     */
    fun dispatch(action: S): R
}

/**
 * Basic contract for all stores. A store implements both [Observable<S>] and [Dispatcher<S, Unit>]
 */
interface Store<out S : Any, in A : Any> : Dispatcher<A, Unit>, Observable<S> {

    /**
     * Dispatches an [ActionCreator<S, A>]
     *
     * @param actionCreator [ActionCreator<S, A>] to dispatch
     */
    fun dispatch(actionCreator: ActionCreator<S, A>)

    /**
     * Dispatches an [AsyncActionCreator<S, A>] to the store
     */
    fun dispatch(asyncActionCreator: AsyncActionCreator<S, A>)
}

/**
 * A middleware is a means of listening to when actions are dispatched to a [Store]. The middleware may choose to dispatch any number of actions back to the store or to alter the action action dispatched to the store itself
 */
interface Middleware<in S, A> {
    /**
     * Invoked when an action is dispatched to the store this [Middleware] is associated with
     *
     * @param store the to which the action has been dispatched to
     * @param state the state at the time the action was dispatched
     * @param next the next dispatcher in the chain
     */
    fun dispatch(store: Dispatcher<A, Unit>, state: S, action: A, next: Dispatcher<A, A?>): A?
}

/**
 * Contract for state stores. State stores are basic stores which maintain a state of type [S] and allow mutation of state only through state mutator functions dispatched to it.
 */
interface StateStore<S : Any> : Store<S, (S) -> S> {

    /**
     * Create a sub state from this store.
     *
     * @param key an arbitrary unique key for the sub state
     * @param init initializer function for the state node
     * @return a [Store<State<S, C>>] that represents both the parent [S] and the child state as a pair]
     */
    fun <C : Any> subState(key: Any, init: () -> C): StateStore<State<S, C>>

    /**
     * Map the state represented by this store by a lens. Mapping allows to reshape the state represented by this store
     *
     * @param lens for mapping state
     * @return mapped state store of type [StateStore<T>]
     */
    fun <T : Any> map(lens: Lens<S, T>): StateStore<T>

    /**
     * Create a reducer store which allows to map state through a reducer function based on the messages dispatched to the store
     *
     * @param reducer a function if type <(S, A) -> S> to map the state based on the current state and the action dispatched
     * @param middleware any number of middlewares to associated with the reducer store
     * @return reducer store
     */
    fun <A : Any> withReducer(reducer: (S, A) -> S, middleware: Iterable<Middleware<S, A>> = emptyList()): Store<S, A>

    companion object {
        /**
         * Factory function to create the root state store
         *
         * @param initialState the initial state of the root state store
         * @return [StateStore<S>]
         */
        operator fun <S : Any> invoke(initialState: S): StateStore<S> =
                DefaultStateStore(
                        rootStateStore = RootStateStore(initialState, Lock()),
                        parentState = Lens(),
                        node = StateNodeRef<S>(),
                        lens = Lens(
                                get = { it.state },
                                set = { state -> { rootNode -> rootNode.copy(state = state) } }
                        ))
    }
}

private class MiddlewareDispatcher<in S : Any, A>(private val store: Dispatcher<A, Unit>,
                                                  private val middleware: Iterable<Middleware<S, A>>) : Dispatcher<A, A?> {
    private lateinit var state: S

    fun onStateChanged(state: S) {
        this.state = state
    }

    override fun dispatch(action: A): A? = ActionDispatcher().dispatch(action)

    private inner class ActionDispatcher : Dispatcher<A, A?> {
        private val middlewareIterator = middleware.iterator()
        override fun dispatch(action: A): A? {
            return if (middlewareIterator.hasNext()) middlewareIterator.next().dispatch(store = store, state = state, action = action, next = this)
            else action
        }
    }
}

private class RootStateStore<R : Any>(initialState: R, private val lock: Lock) {
    private var state by Delegates.observable(StateNode(initialState)) { _, oldState, newState ->
        if (newState != oldState) subscriptions.keys.forEach { subscriber -> subscriber(newState) }
    }
    @Volatile
    private var subscriptions = emptyMap<(StateNode<R>) -> Unit, Subscription>()
    @Volatile
    private var isDispatching = false

    fun dispatch(action: (StateNode<R>) -> StateNode<R>) {
        if (isDispatching) throw IllegalStateException("an action is already being dispatched")

        isDispatching = true
        state = try {
            action(state)
        } finally {
            isDispatching = false
        }
    }

    fun dispatch(actionCreator: ActionCreator<StateNode<R>, (StateNode<R>) -> StateNode<R>>) {
        lock {
            actionCreator(state)?.let(::dispatch)
        }
    }

    fun dispatch(asyncActionCreator: AsyncActionCreator<StateNode<R>, (StateNode<R>) -> StateNode<R>>) = lock {
        asyncActionCreator(state) {
            dispatch(it)
        }
    }

    fun subscribe(block: (StateNode<R>) -> Unit): Subscription = lock {
        var subscription = subscriptions[block]
        if (subscription == null) {
            subscription = Subscription {
                subscriptions -= block
            }
            subscriptions += block to subscription
            block(state)
        }
        subscription
    }
}

private class DefaultStateStore<R : Any, S : Any, P : Any, M : Any>(private val rootStateStore: RootStateStore<R>,
                                                                    private val parentState: Lens<StateNode<R>, P>,
                                                                    private val node: StateNodeRef<R, S>,
                                                                    private val lens: Lens<State<P, S>, M>) : StateStore<M> {
    private val state = Lens<StateNode<R>, State<P, S>>(
            get = { State(parentState(it), node.value(it)) },
            set = { state -> { rootNode -> node.value(parentState(rootNode, state.parentState), state.state) } }
    ) + lens

    override fun dispatch(action: (M) -> M) {
        rootStateStore.dispatch { rootState ->
            state(rootState, action(state(rootState)))
        }
    }

    override fun dispatch(actionCreator: ActionCreator<M, (M) -> M>) {
        rootStateStore.dispatch(object : ActionCreator<StateNode<R>, (StateNode<R>) -> StateNode<R>> {
            override fun invoke(state: StateNode<R>) =
                    actionCreator(state(state))?.let { action ->
                        { rootNode: StateNode<R> -> state(rootNode, action(state(rootNode))) }
                    }
        })
    }

    override fun dispatch(asyncActionCreator: AsyncActionCreator<M, (M) -> M>) {
        rootStateStore.dispatch(object : AsyncActionCreator<StateNode<R>, (StateNode<R>) -> StateNode<R>> {
            override fun invoke(state: StateNode<R>, dispatcher: (ActionCreator<StateNode<R>, (StateNode<R>) -> StateNode<R>>) -> Unit) {
                asyncActionCreator(state(state)) { dispatch(it) }
            }
        })
    }

    override fun <C : Any> subState(key: Any, init: () -> C): StateStore<State<M, C>> =
            DefaultStateStore(
                    rootStateStore = rootStateStore,
                    node = node.addChild(key, init),
                    parentState = state,
                    lens = Lens())

    override fun <T : Any> map(lens: Lens<M, T>): StateStore<T> =
            DefaultStateStore(rootStateStore = rootStateStore,
                    parentState = parentState,
                    node = node,
                    lens = this.lens + lens)

    override fun <A : Any> withReducer(reducer: (M, A) -> M, middleware: Iterable<Middleware<M, A>>): Store<M, A> =
            ReducerStore(this, reducer, middleware)

    override fun subscribe(block: (M) -> Unit): Subscription = rootStateStore.subscribe(Subscriber(block, state))

    private data class Subscriber<in R : Any, M : Any>(private val block: (M) -> Unit,
                                                       private val state: Lens<StateNode<R>, M>) : (StateNode<R>) -> Unit {
        override fun invoke(rootNode: StateNode<R>) = block(state(rootNode))
    }
}

private class ReducerStore<S : Any, in A : Any>(private val store: Store<S, (S) -> S>,
                                                private val reducer: (S, A) -> S,
                                                middleware: Iterable<Middleware<S, A>>) : Store<S, A> {
    private val middlewareDispatcher = MiddlewareDispatcher(this, middleware).apply {
        subscribe(::onStateChanged)
    }

    override fun subscribe(block: (S) -> Unit): Subscription = store.subscribe(block)

    override fun dispatch(action: A) {
        middlewareDispatcher.dispatch(action)?.let { dispatchedAction ->
            store.dispatch { reducer(it, dispatchedAction) }
        }
    }

    override fun dispatch(actionCreator: ActionCreator<S, A>) {
        store.dispatch(object : ActionCreator<S, (S) -> S> {
            override fun invoke(state: S): ((S) -> S)? = actionCreator(state)?.let { action ->
                { reducer(state, action) }
            }
        })
    }

    override fun dispatch(asyncActionCreator: AsyncActionCreator<S, A>) {
        store.dispatch(object : AsyncActionCreator<S, (S) -> S> {
            override fun invoke(state: S, dispatcher: (ActionCreator<S, (S) -> S>) -> Unit) {
                asyncActionCreator(state) { actionCreator ->
                    dispatch(actionCreator)
                }
            }
        })
    }
}
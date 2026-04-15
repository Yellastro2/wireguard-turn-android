package com.yellastrodev.rknvpn.fragment

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.yellastrodev.rknvpn.R
import com.yellastrodev.rknvpn.activity.BaseActivity
import com.yellastrodev.rknvpn.model.ObservableTunnel
import com.yellastrodev.rknvpn.model.TunnelVkLinkException
import com.yellastrodev.rnkvpn.rnkutils.CallResult
import com.yellastrodev.rnkvpn.viewmodel.TunnelViewModel
import com.yellastrodev.rnkvpn.viewmodel.ViewTunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RNKHomeFragment : BaseFragment() {

    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var pulseView: View
    private lateinit var mainButton: FrameLayout
    private lateinit var logoImage: ImageView
    private lateinit var btnAuthorities: View


    private var pulseAnimator: ValueAnimator? = null

    private val viewModel: TunnelViewModel by activityViewModels()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d("RNKHomeFragment", "[onCreateView]")
        val view = inflater.inflate(R.layout.rnk_home_frag, container, false)

        statusTitle = view.findViewById(R.id.statusTitle)
        statusDesc = view.findViewById(R.id.statusDesc)
        pulseView = view.findViewById(R.id.pulseView)
        mainButton = view.findViewById(R.id.mainButton)
        logoImage = view.findViewById(R.id.logoImage)
        btnAuthorities = view.findViewById(R.id.btnAuthorities)

        btnAuthorities.setOnClickListener {
            requireActivity().supportFragmentManager.commit {
                replace(R.id.list_detail_container, RNKTunnelListFragment())  // ← главный контейнер
                setTransition(androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                addToBackStack(null)
            }
        }


        mainButton.setOnClickListener {
            handleButtonClick()
        }

        mainButton.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Уменьшаем кнопку при нажатии
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // Возвращаем исходный размер
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
            }
            false // false позволяет OnClickListener работать дальше
        }


        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { currentState ->
                    when (currentState) {
                        is ViewTunnelState.Idle -> {
                            setTunnelDisable()
                        }
                        is ViewTunnelState.Connecting -> {
                            setTunnelConnecting()
                        }
                        is ViewTunnelState.Active -> {
                            setTunnelEnable()
                        }
                        is ViewTunnelState.Error -> {

                        }
                    }
                }
            }
        }


        return view
    }

    override fun onResume() {
        super.onResume()
        selectedTunnel?.let { currentTunnel ->
            updateUI(currentTunnel)
        } ?: run {
            startPulseAnimation()
        }
    }

    private fun handleButtonClick() {
        val tunnel = selectedTunnel

        // Лог оставляем как был, но учитываем null
        Log.d("RNKHomeFragment", "[handleButtonClick], состояние VPN: ${tunnel?.state?.name ?: "NULL (туннель не выбран)"}")

        if (tunnel == null) {
            // Если туннель не выбран, выводим диалог "Полномочия не обнаружены"
            showWarningDialog(
                title = "ДОСТУП ЗАПРЕЩЕН",
                message = "Полномочия не обнаружены. Укажите узел связи для инспекции трафика.",
                confirmText = "Указать полномочия",
                isNoTunnelMode = true
            )
//        } else if (tunnel.state == Tunnel.State.DOWN) {
        } else if (viewModel.state.value == ViewTunnelState.Idle) {

            // Если VPN выключен (Защита включена), показываем стандартный ворнинг
            showWarningDialog(
                title = "ВНИМАНИЕ",
                message = "Отключение Роскомнадзор инспектора опасно и нарушает суверенитет нашей страны.",
                confirmText = "Я уполномочен, отключай",
                isNoTunnelMode = false
            )
        } else {
            // Если VPN включен (Защита отключена), просто возвращаем "защиту" назад
            setTunnelState(tunnel, Tunnel.State.DOWN)
        }
    }

    private fun showWarningDialog(
        title: String,
        message: String,
        confirmText: String,
        isNoTunnelMode: Boolean
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_warning, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Находим элементы (проверь ID в своем XML или добавь их)
        // Если ID нет, можно найти так:
        val tvTitle = dialogView.findViewById<TextView>(R.id.dial_warn_title) // Это где "ВНИМАНИЕ"
        val tvMessage = dialogView.findViewById<TextView>(R.id.dial_warn_body) // Это где длинный текст
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnConfirm)

        tvTitle.text = title
        tvMessage.text = message
        btnConfirm.text = confirmText

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnConfirm).setOnClickListener {
            dialog.dismiss()
            if (isNoTunnelMode) {
                // Лог навигации
                Log.d("RNKHomeFragment", "Переход в список туннелей (указание полномочий)")
                requireActivity().supportFragmentManager.commit {
                    replace(R.id.list_detail_container, RNKTunnelListFragment())
                    setTransition(androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    addToBackStack(null)
                }
            } else {
                activateTunnel()
            }
        }

        dialog.show()
    }

    private fun activateTunnel(){


        requireActivity().lifecycleScope.launch {
            if (! checkTunnelPermission(requireView())) return@launch
        }

        viewModel.updateState(ViewTunnelState.Connecting)

        selectedTunnel?.let { tunnel ->
            requireActivity().lifecycleScope.launch(Dispatchers.IO) {
                if (citizennKey?.startsWith("$") == true) {
                    Log.d("RNKHomeFragment", "ключ доступа есть, делаем новую ссылку")
                    Snackbar.make(requireView(), "СОГЛАСОВАНИЕ ОСОБЫХ ПОЛНОМОЧИЙ", Snackbar.LENGTH_LONG).show()
                    val linkSuccess = (requireActivity() as BaseActivity).vkSessionManager.getLinkSmarter(citizennKey!!).let { result ->
                        when (result) {
                            is CallResult.Success -> {
                                (requireActivity() as BaseActivity).setVkLink(result.url, tunnel)
                                return@let true //
                            }

                            is CallResult.AuthExpired -> {
                                Snackbar.make(requireView(), "ОШИБКА СОГЛАСОВАНИЯ ОСОБЫХ ПОЛНОМОЧИЙ ПРОВЕРЬТЕ СВОЙ КЛЮЧ ГРАЖДАНИНА", Snackbar.LENGTH_LONG)
                                    .show()

                                return@let false
                            }

                            is CallResult.Error -> {
                                Snackbar.make(requireView(), "НЕПРЕДВИДЕННАЯ ОШИБКА СОГЛАСОВАНИЯ ПОЛНОМОЧИЙ: ${result.message}", Snackbar.LENGTH_LONG)
                                    .show()
                                return@let false
                            }
                        }
                    }

                    if (!linkSuccess)
                        return@launch
                }
                setTunnelState(tunnel, Tunnel.State.UP)
            }
        }
    }

    private fun setTunnelState(tunnel: ObservableTunnel, state: Tunnel.State) {
        Log.d("RNKHomeFragment", "Установка состояния VPN: ${state.name}")
        lifecycleScope.launch {
            try {

                Log.d("RNKHomeFragment", "[setTunnelState] Установка состояния VPN: ${state.name}")
                tunnel.setStateAsync(state)

            } catch (e: TunnelVkLinkException) {
                if (citizennKey?.startsWith("http") == true)
                    Snackbar.make(requireView(), "ОШИБКА ОТКЛЮЧЕНИЯ ОБНОВИТЕ КЛЮЧ ГРАЖДАНИНА", Snackbar.LENGTH_LONG).show()
                else {
                    Log.w("RNKHomeFragment", "Ошибка открытия туннеля, обновим внутренние полномочия..", e)
                    Snackbar.make(requireView(), "Ошибка открытия туннеля, обновим внутренние полномочия..", Snackbar.LENGTH_LONG).show()

//                    citizennKey?.let{
//                            // Вызываем, получаем результат и сразу скармливаем его в when
//                        (requireActivity() as BaseActivity).vkSessionManager.getLinkSmarter(it).let { result ->
//                            when (result) {
//                                is CallResult.Success -> {
//                                    (requireActivity() as BaseActivity).setVkLink(result.url, tunnel)
//                                    try {
//                                        tunnel.setStateAsync(state)
//                                    } catch (e: Throwable) {
//                                        Log.e("RNKHomeFragment", "[setTunnelState] Ошибка при изменении состояния VPN после ошибки и пересброса вклинк", e)
//                                        Snackbar.make(requireView(), "ОШИБКА ОТКРЫТИЯ ДОСТУПА К УЗЛУ", Snackbar.LENGTH_LONG).show()
//                                    }
//                                }
//                                is CallResult.AuthExpired ->
//                                    Snackbar.make(requireView(), "ОШИБКА СОГЛАСОВАНИЯ ОСОБЫХ ПОЛНОМОЧИЙ", Snackbar.LENGTH_LONG).show()
//
//                                is CallResult.Error ->
//                                    Snackbar.make(requireView(), "НЕПРЕДВИДЕННАЯ ОШИБКА СОГЛАСОВАНИЯ ПОЛНОМОЧИЙ: ${result.message}", Snackbar.LENGTH_LONG).show()
//                            }
//                        }
//                    } ?: run {
//                        Snackbar.make(requireView(), "ОШИБКА ОТКЛЮЧЕНИЯ ОБНОВИТЕ КЛЮЧ ГРАЖДАНИНА", Snackbar.LENGTH_LONG).show()
//                    }
                }
            } catch (e: BackendException) {
                // Обработка ошибок (например, через Snackbar)
                Log.e("RNKHomeFragment", "Ошибка при изменении состояния VPN", e)
                if (e.reason ==  BackendException.Reason.VPN_NOT_AUTHORIZED) {

                    Log.e("RNKHomeFragment", "Нет разрешения на запуск впн")
                    GoBackend.VpnService.prepare(context)
                }
            }
            catch (e: Throwable) {
                // Обработка ошибок (например, через Snackbar)
                Log.e("RNKHomeFragment", "Ошибка при изменении состояния VPN", e)
                Snackbar.make(requireView(), "Ошибка: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    // Внутри класса RNKHomeFragment
    private val tunnelStateCallback = object : androidx.databinding.Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: androidx.databinding.Observable?, propertyId: Int) {
            // BR.state — это ID поля state в сгенерированном классе ресурсов DataBinding
            if (propertyId == com.yellastrodev.rknvpn.BR.state) {
                val tunnel = sender as? ObservableTunnel
                Log.d("RNKHomeFragment", "Статус туннеля изменился на: ${tunnel?.state}")

                // Обновляем UI в главном потоке
                lifecycleScope.launch {
                    if (tunnel?.state == Tunnel.State.DOWN)
                        viewModel.updateState(ViewTunnelState.Idle)
//                    updateUI(tunnel)
                }
            }
        }
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        Log.d("RNKHomeFragment", "[onSelectedTunnelChanged] oldTunnel=$oldTunnel, newTunnel=$newTunnel")

        oldTunnel?.removeOnPropertyChangedCallback(tunnelStateCallback)

        // Подписываемся на новый
        newTunnel?.addOnPropertyChangedCallback(tunnelStateCallback)

        updateUI(newTunnel)
    }


    private fun setTunnelDisable(){
        statusTitle.text = "Защита суверенитета включена"
        statusTitle.setTextColor(Color.parseColor("#059669"))
        statusDesc.text = "Трафик инспектируется. Угроз не обнаручено."

        mainButton.setBackgroundResource(R.drawable.bg_button_protected)
        logoImage.setImageResource(R.drawable.ic_logo_protected)

        startPulseAnimation()
    }

    private fun setTunnelEnable(){
        statusTitle.text = "Защита суверенитета отключена"
        statusTitle.setTextColor(Color.parseColor("#DC2626"))
        statusDesc.text = "Инспектор отключен. Соединение уязвимо."

        mainButton.setBackgroundResource(R.drawable.bg_button_unprotected)
        logoImage.setImageResource(R.drawable.ic_logo_unprotected)

        stopPulseAnimation()
    }

    private fun setTunnelConnecting(){
        statusTitle.text = "ОТКЛЮЧЕНИЕ. ОЖИДАЙТЕ"
        statusTitle.setTextColor(Color.parseColor("#DC2626"))
        statusDesc.text = "Инспектор отключен. Соединение уязвимо."

        mainButton.setBackgroundResource(R.drawable.bg_button_unprotected)
        logoImage.setImageResource(R.drawable.ic_logo_unprotected)

//        stopPulseAnimation()
    }

    private fun updateUI(tunnel: ObservableTunnel?) {
        Log.d("RNKHomeFragment", "[updateUI] туннеля: ${tunnel?.name} ${tunnel?.state?.name}")

        // Логика РКН: если VPN DOWN — значит защита ВКЛЮЧЕНА (зеленый)
        val isDangeur = tunnel?.state == Tunnel.State.UP
        Log.d("RNKHomeFragment", "[updateUI] isDangeur: $isDangeur")

        if (!isDangeur) {
            setTunnelDisable()
        } else {
            setTunnelEnable()
        }
    }

    private fun startPulseAnimation() {
        if (pulseAnimator?.isRunning == true) {
            pulseAnimator?.cancel()
            pulseAnimator = null
        }
        Log.d("RNKHomeFragment","[startPulseAnimation] запуск анимации")
        if (pulseAnimator == null) {

            Log.d("RNKHomeFragment","[startPulseAnimation] Создание анимации")
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.5f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.5f)
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 0f)

            pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(pulseView, scaleX, scaleY, alpha).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
            }
        }
        pulseView.visibility = View.VISIBLE
        pulseAnimator?.start()
    }

    private fun stopPulseAnimation() {
        Log.d("RNKHomeFragment","[stopPulseAnimation]")
        pulseAnimator?.cancel()
        pulseView.visibility = View.GONE
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Если туннель уже выбран в Activity (например, при возврате из списка)
        selectedTunnel?.addOnPropertyChangedCallback(tunnelStateCallback)
    }

    override fun onDestroyView() {
        // ОБЯЗАТЕЛЬНО отписываемся при уничтожении View фрагмента
        selectedTunnel?.removeOnPropertyChangedCallback(tunnelStateCallback)
        stopPulseAnimation()
        pulseAnimator = null // Грохаем старый аниматор, чтобы в startPulseAnimation создался новый для новой вьюхи
        super.onDestroyView()
    }
}
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.backend.Tunnel
import com.yellastrodev.rknvpn.Application
import com.yellastrodev.rknvpn.R
import com.yellastrodev.rknvpn.model.ObservableTunnel
import com.yellastrodev.rnkvpn.fragment.RNKFragmentTunnelEditor
import kotlinx.coroutines.launch

class RNKHomeFragment : BaseFragment() {

    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var pulseView: View
    private lateinit var mainButton: FrameLayout
    private lateinit var logoImage: ImageView
    private lateinit var btnAuthorities: View

    private var pulseAnimator: ValueAnimator? = null


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

//        selectedTunnel?.let { currentTunnel ->
//            updateUI(currentTunnel)
//        } ?: run {
//            startPulseAnimation()
//        }


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
        } else if (tunnel.state == Tunnel.State.DOWN) {
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
                // Твой старый добрый лог внутри setTunnelState сработает
                selectedTunnel?.let { setTunnelState(it, Tunnel.State.UP) }
            }
        }

        dialog.show()
    }

    private fun setTunnelState(tunnel: ObservableTunnel, state: Tunnel.State) {
        Log.d("RNKHomeFragment", "Установка состояния VPN: ${state.name}")
        lifecycleScope.launch {
            try {
                tunnel.setStateAsync(state)
            } catch (e: Throwable) {
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
                    updateUI(tunnel)
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

    private fun updateUI(tunnel: ObservableTunnel?) {
        Log.d("RNKHomeFragment", "[updateUI] туннеля: ${tunnel?.name} ${tunnel?.state?.name}")

        // Логика РКН: если VPN DOWN — значит защита ВКЛЮЧЕНА (зеленый)
        val isDangeur = tunnel?.state == Tunnel.State.UP
        Log.d("RNKHomeFragment", "[updateUI] isDangeur: $isDangeur")

        if (!isDangeur) {
            statusTitle.text = "Защита суверенитета включена"
            statusTitle.setTextColor(Color.parseColor("#059669"))
            statusDesc.text = "Трафик инспектируется. Угроз не обнаручено."

            mainButton.setBackgroundResource(R.drawable.bg_button_protected)
            logoImage.setImageResource(R.drawable.ic_logo_protected)

            startPulseAnimation()
        } else {
            statusTitle.text = "Защита суверенитета отключена"
            statusTitle.setTextColor(Color.parseColor("#DC2626"))
            statusDesc.text = "Инспектор отключен. Соединение уязвимо."

            mainButton.setBackgroundResource(R.drawable.bg_button_unprotected)
            logoImage.setImageResource(R.drawable.ic_logo_unprotected)

            stopPulseAnimation()
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
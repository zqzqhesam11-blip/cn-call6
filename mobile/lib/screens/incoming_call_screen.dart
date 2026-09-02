import 'package:flutter/material.dart';

class IncomingCallScreen extends StatefulWidget {
  final String name;
  final String id;
  final String callId;
  final Future<void> Function() onAccept;
  final Future<void> Function() onReject;

  const IncomingCallScreen({
    super.key,
    required this.name,
    required this.id,
    required this.callId,
    required this.onAccept,
    required this.onReject,
  });

  @override
  State<IncomingCallScreen> createState() => _IncomingCallScreenState();
}

class _IncomingCallScreenState extends State<IncomingCallScreen>
    with SingleTickerProviderStateMixin {
  double _drag = 0;
  bool _busy = false;
  late final AnimationController _pulse;

  @override
  void initState() {
    super.initState();
    _pulse = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1400),
    )..repeat(reverse: true);
  }

  @override
  void dispose() {
    _pulse.dispose();
    super.dispose();
  }

  Future<void> _accept() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await widget.onAccept();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _reject() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await widget.onReject();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _onDragUpdate(DragUpdateDetails details) {
    if (_busy) return;
    setState(() {
      _drag += details.delta.dx;
      _drag = _drag.clamp(-150.0, 150.0);
    });
  }

  Future<void> _onDragEnd(DragEndDetails details) async {
    if (_busy) return;

    if (_drag > 90) {
      setState(() => _drag = 0);
      await _accept();
      return;
    }

    if (_drag < -90) {
      setState(() => _drag = 0);
      await _reject();
      return;
    }

    setState(() => _drag = 0);
  }

  @override
  Widget build(BuildContext context) {
    final safeName =
        widget.name.trim().isEmpty ? 'مستخدم CN CALL' : widget.name.trim();

    return Directionality(
      textDirection: TextDirection.rtl,
      child: Scaffold(
        backgroundColor: const Color(0xFF050607),
        body: SafeArea(
          child: Stack(
            children: [
              Positioned.fill(
                child: IgnorePointer(
                  child: AnimatedBuilder(
                    animation: _pulse,
                    builder: (_, _) {
                      final scale = 1.0 + (_pulse.value * .06);
                      return Center(
                        child: Transform.scale(
                          scale: scale,
                          child: Container(
                            width: 230,
                            height: 230,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              border: Border.all(
                                color: const Color(0xFF1FDF85)
                                    .withValues(alpha: .05),
                                width: 18,
                              ),
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
              ),
              Column(
                children: [
                  const SizedBox(height: 42),

                  Text(
                    'مكالمة واردة',
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: .72),
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),

                  const Spacer(),

                  Hero(
                    tag: 'call-avatar-${widget.callId}',
                    child: Container(
                      width: 132,
                      height: 132,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: const Color(0xFF15191B),
                        border: Border.all(
                          color: const Color(0xFF27E889)
                              .withValues(alpha: .32),
                          width: 2,
                        ),
                        boxShadow: [
                          BoxShadow(
                            color: const Color(0xFF27E889)
                                .withValues(alpha: .12),
                            blurRadius: 34,
                            spreadRadius: 7,
                          ),
                        ],
                      ),
                      alignment: Alignment.center,
                      child: Text(
                        safeName.characters.first.toUpperCase(),
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 54,
                          fontWeight: FontWeight.w300,
                        ),
                      ),
                    ),
                  ),

                  const SizedBox(height: 24),

                  Text(
                    safeName,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 30,
                      fontWeight: FontWeight.w800,
                    ),
                  ),

                  const SizedBox(height: 8),

                  Text(
                    widget.id,
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: .42),
                      fontSize: 14,
                    ),
                  ),

                  const SizedBox(height: 12),

                  Text(
                    'يتصل بك الآن',
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: .56),
                      fontSize: 14,
                    ),
                  ),

                  const Spacer(),

                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 28),
                    child: Column(
                      children: [
                        Text(
                          'اسحب للرد أو الرفض',
                          style: TextStyle(
                            color: Colors.white.withValues(alpha: .34),
                            fontSize: 12,
                          ),
                        ),
                        const SizedBox(height: 14),

                        Container(
                          height: 76,
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(38),
                            color: const Color(0xFF121618),
                            border: Border.all(
                              color: Colors.white.withValues(alpha: .06),
                            ),
                          ),
                          child: LayoutBuilder(
                            builder: (context, constraints) {
                              final center = constraints.maxWidth / 2;
                              final handleX =
                                  (center + _drag).clamp(38.0, constraints.maxWidth - 38);

                              return Stack(
                                children: [
                                  PositionedDirectional(
                                    start: 18,
                                    top: 0,
                                    bottom: 0,
                                    child: Center(
                                      child: _MiniAction(
                                        icon: Icons.call_end_rounded,
                                        color: const Color(0xFFFF4D5A),
                                        label: 'رفض',
                                      ),
                                    ),
                                  ),
                                  PositionedDirectional(
                                    end: 18,
                                    top: 0,
                                    bottom: 0,
                                    child: Center(
                                      child: _MiniAction(
                                        icon: Icons.call_rounded,
                                        color: const Color(0xFF35E890),
                                        label: 'قبول',
                                      ),
                                    ),
                                  ),
                                  Positioned(
                                    left: handleX - 30,
                                    top: 1,
                                    child: GestureDetector(
                                      onHorizontalDragUpdate: _onDragUpdate,
                                      onHorizontalDragEnd: _onDragEnd,
                                      child: Container(
                                        width: 60,
                                        height: 60,
                                        decoration: BoxDecoration(
                                          shape: BoxShape.circle,
                                          color: _drag >= 0
                                              ? const Color(0xFF24D981)
                                              : const Color(0xFFFF4D5A),
                                          boxShadow: [
                                            BoxShadow(
                                              color: (_drag >= 0
                                                      ? const Color(0xFF24D981)
                                                      : const Color(0xFFFF4D5A))
                                                  .withValues(alpha: .28),
                                              blurRadius: 20,
                                              spreadRadius: 2,
                                            ),
                                          ],
                                        ),
                                        child: Icon(
                                          _drag >= 0
                                              ? Icons.call_rounded
                                              : Icons.call_end_rounded,
                                          color: Colors.white,
                                          size: 27,
                                        ),
                                      ),
                                    ),
                                  ),
                                ],
                              );
                            },
                          ),
                        ),

                        const SizedBox(height: 20),

                        Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            TextButton.icon(
                              onPressed: _busy ? null : _reject,
                              icon: const Icon(Icons.notifications_none_rounded),
                              label: const Text('تذكير لاحقًا'),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),

                  const SizedBox(height: 28),
                ],
              ),

              if (_busy)
                Positioned.fill(
                  child: ColoredBox(
                    color: Colors.black.withValues(alpha: .25),
                    child: const Center(
                      child: SizedBox(
                        width: 28,
                        height: 28,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MiniAction extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String label;

  const _MiniAction({
    required this.icon,
    required this.color,
    required this.label,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, color: color, size: 20),
        const SizedBox(width: 4),
        Text(
          label,
          style: TextStyle(
            color: color.withValues(alpha: .75),
            fontSize: 10,
            fontWeight: FontWeight.w700,
          ),
        ),
      ],
    );
  }
}
